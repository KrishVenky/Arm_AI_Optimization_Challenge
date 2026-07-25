package com.audimus.analysis

/*
 * ----------------------------------------------------------------------------------------------
 * LiteRT-LM API notes (verified 2026-07-11 against docs for litertlm-android 0.14.0:
 *   https://developers.google.com/edge/litert-lm/android and
 *   https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/.../Config.kt)
 *
 *   VERIFIED signatures used below:
 *     - SamplerConfig(topK: Int, topP: Double, temperature: Double, seed: Int = 0)
 *         -> topP / temperature are Double, NOT Float. (The task brief's `0.95f` would NOT compile.)
 *     - EngineConfig(modelPath: String, backend: Backend = Backend.CPU(), ...)
 *     - Backend.CPU() / Backend.GPU() are invoked WITH parentheses (CPU is a data class, GPU a class).
 *     - Engine(config).initialize() / .close() are BLOCKING  -> always called off the main thread.
 *     - engine.createConversation(ConversationConfig(...)): Conversation
 *     - conversation.sendMessage(String): Message ; the reply text is in Message.contents.contents
 *       as Content.Text parts (there is NO Message.text) ; conversation.close()
 *     - Contents.of("...") accepts a plain String for the system instruction.
 *
 *   REMAINING UNCERTAINTY (contained to this file — see below):
 *     - initialize()'s return type is treated as ignorable; success/failure is inferred from
 *       whether it throws, not from a returned status.
 *     - If any of the above drifts in a future point release, ONLY this class fails to build;
 *       CallAnalyzer + StubCallAnalyzer carry no LiteRT-LM references, so the stub path stays usable.
 * ----------------------------------------------------------------------------------------------
 */

import android.content.Context
import android.util.Log
import com.audimus.model.AnalysisResult
import com.audimus.model.MeetingMention
import com.audimus.model.RiskLevel
import com.audimus.model.TaskMention
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Real on-device analyzer backed by an LLM (Gemma) running through LiteRT-LM.
 *
 * Design:
 *  - The engine is loaded once in [initialize] and reused. Each [analyze] pass spins up a
 *    fresh [Conversation], sends the whole running transcript in a single prompt, then closes it.
 *    Statelessness per pass keeps results deterministic w.r.t. the transcript and prevents the
 *    model from re-reading its own earlier JSON; "context across the call" is supplied by
 *    re-embedding the full transcript every pass, not by conversation memory.
 *  - All LiteRT-LM calls are blocking, so they run on [Dispatchers.Default] and are serialized by
 *    [mutex] so an [initialize] and/or two [analyze] passes never overlap.
 *  - Nothing here throws to the caller: missing model / init failure / malformed output all
 *    resolve to `false` or `null` with a logged reason.
 *
 * @param context used only to resolve the model path under the app's external files dir.
 * @param backend inference backend; defaults to CPU for the widest device compatibility.
 */
class GemmaCallAnalyzer(
    private val context: Context,
    private val backend: Backend = Backend.CPU(),
) : CallAnalyzer {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** Serializes init + analyze so blocking LiteRT-LM passes never overlap. */
    private val mutex = Mutex()

    @Volatile
    private var engine: Engine? = null

    /** Expected on-device location of the model, e.g. .../files/models/gemma-4-E4B-it.litertlm */
    private val modelFile: File
        get() = File(context.getExternalFilesDir(MODELS_DIR), MODEL_FILE_NAME)

    override suspend fun initialize(): Boolean = mutex.withLock {
        if (engine != null) {
            _isReady.value = true
            return@withLock true
        }

        val file = modelFile
        if (!file.exists()) {
            Log.w(
                TAG,
                "Model not found — LLM analysis unavailable. Push the model to: ${file.absolutePath} " +
                    "(expected file name: $MODEL_FILE_NAME). Falling back to the non-LLM analyzer.",
            )
            _isReady.value = false
            return@withLock false
        }

        withContext(Dispatchers.Default) {
            try {
                val config = EngineConfig(modelPath = file.absolutePath, backend = backend)
                val created = Engine(config)
                created.initialize() // blocking, may take several seconds
                engine = created
                _isReady.value = true
                Log.i(TAG, "LiteRT-LM engine initialized from ${file.absolutePath}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize LiteRT-LM engine", t)
                runCatching { engine?.close() }
                engine = null
                _isReady.value = false
                false
            }
        }
    }

    override suspend fun analyze(transcript: String): AnalysisResult? {
        if (transcript.isBlank()) return null

        return mutex.withLock {
            val activeEngine = engine
            if (activeEngine == null) {
                Log.w(TAG, "analyze() called before a ready engine; ignoring")
                return@withLock null
            }

            withContext(Dispatchers.Default) {
                var conversation: Conversation? = null
                try {
                    conversation = activeEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                            samplerConfig = SamplerConfig(
                                topK = 40,
                                topP = 0.95,
                                temperature = 0.8,
                            ),
                        ),
                    )
                    val reply = conversation.sendMessage(buildUserPrompt(transcript))
                    parseResult(extractText(reply))
                } catch (t: Throwable) {
                    Log.e(TAG, "analyze() failed", t)
                    null
                } finally {
                    runCatching { conversation?.close() }
                }
            }
        }
    }

    override fun close() {
        _isReady.value = false
        runCatching { engine?.close() }
        engine = null
    }

    /**
     * A LiteRT-LM reply is a [Message] whose [Contents] hold a list of typed [Content] parts; the
     * generated text lives in the [Content.Text] parts. Concatenate them (ignoring any non-text
     * parts) to recover the model's textual answer.
     */
    private fun extractText(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    // ---------------------------------------------------------------------------------------------
    // Prompt construction
    // ---------------------------------------------------------------------------------------------

    /** Embeds the transcript and re-states the required output contract for a single pass. */
    private fun buildUserPrompt(transcript: String): String = buildString {
        append("Analyze the LIVE PHONE CALL TRANSCRIPT below for scam/fraud risk, and extract any ")
        append("proposed meeting and any explicit task.\n\n")
        append("Return EXACTLY ONE JSON object and NOTHING ELSE — no markdown fences, no commentary — ")
        append("matching this schema exactly:\n")
        append(JSON_SCHEMA)
        append("\n\nRules:\n")
        append("- riskLevel is HIGH when the caller pressures for OTP/PIN/passwords, bank or card ")
        append("details, gift cards, wire transfers, remote-access installs, or impersonates a ")
        append("bank/government/authority with urgency or threats.\n")
        append("- riskLevel is MEDIUM for softer pressure (urgency, \"verify your account\", ")
        append("\"suspended\") without a hard credential/payment ask; otherwise LOW.\n")
        append("- Never use double-quote characters inside any string value, including \"reason\": ")
        append("paraphrase or use single quotes instead of quoting the caller's exact words, since a ")
        append("literal double quote inside a string breaks JSON.\n")
        append("- \"meeting\" is null unless a specific meeting/appointment is proposed.\n")
        append("- \"task\" is null unless an explicit action item is requested; \"assignee\" is who must do it.\n")
        append("- \"confidence\" is your certainty in riskLevel, between 0.0 and 1.0.\n\n")
        append("TRANSCRIPT:\n<transcript>\n")
        append(transcript)
        append("\n</transcript>")
    }

    // ---------------------------------------------------------------------------------------------
    // Defensive parsing (org.json — Android built-in). Never throws.
    // ---------------------------------------------------------------------------------------------

    /** Maps the model's raw reply to an [AnalysisResult], or `null` if it can't be understood. */
    private fun parseResult(raw: String?): AnalysisResult? {
        if (raw.isNullOrBlank()) {
            Log.w(TAG, "Empty model reply")
            return null
        }
        val jsonText = extractFirstJsonObject(raw) ?: run {
            Log.w(TAG, "No JSON object found in model reply")
            return null
        }
        return try {
            val obj = JSONObject(jsonText)

            val risk = parseRisk(obj.stringOrNull("riskLevel"))
            val reason = obj.stringOrNull("reason") ?: "No reason provided."
            val confidence = obj.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)

            val meeting = obj.optJSONObject("meeting")?.let { m ->
                val title = m.stringOrNull("title")
                val whenText = m.stringOrNull("whenText")
                val person = m.stringOrNull("personName")
                if (title == null && whenText == null) {
                    null
                } else {
                    MeetingMention(
                        title = title ?: "",
                        whenText = whenText ?: "",
                        personName = person,
                    )
                }
            }

            val task = obj.optJSONObject("task")?.let { t ->
                val text = t.stringOrNull("text")
                val assignee = t.stringOrNull("assignee")
                if (text == null) null else TaskMention(text = text, assignee = assignee)
            }

            AnalysisResult(
                riskLevel = risk,
                reason = reason,
                confidence = confidence,
                meeting = meeting,
                task = task,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse model JSON: $jsonText", t)
            null
        }
    }

    /** LOW/MEDIUM/HIGH (case-insensitive); anything unknown maps to LOW. */
    private fun parseRisk(value: String?): RiskLevel = when (value?.trim()?.uppercase()) {
        "HIGH" -> RiskLevel.HIGH
        "MEDIUM" -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    /**
     * Returns the first balanced `{...}` block in [raw], tolerating leading/trailing prose and
     * markdown fences. String literals (and their escapes) are respected so braces inside quoted
     * text don't throw off the depth count.
     */
    private fun extractFirstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Null-safe string read: returns `null` for absent keys, JSON `null`, the literal string
     * "null" (Android's [JSONObject.optString] renders JSON null as "null"), and blanks.
     */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key).trim()
        return if (value.isEmpty() || value.equals("null", ignoreCase = true)) null else value
    }

    private companion object {
        const val TAG = "GemmaCallAnalyzer"
        const val MODELS_DIR = "models"
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

        val SYSTEM_INSTRUCTION = """
            You are Audimus, an on-device scam-shield analyst embedded in a live phone-call assistant.
            You continuously reason over the WHOLE running transcript of the current call, holding
            context across turns and tracking manipulation tactics: manufactured urgency, spoofed
            authority (bank, police, government, tax office, tech support), threats (arrest, account
            suspension, legal action), and any request for one-time passwords, PINs, passwords, bank
            or card details, gift-card codes, wire transfers, or remote-access software installs.
            You also extract any proposed meeting (its topic, the spoken date/time, and the named
            person) and any explicit task/action item together with who is responsible for it.
            You never accuse without evidence, but you err toward protecting the user.
            You ALWAYS respond with exactly ONE JSON object and no other text.
        """.trimIndent()

        val JSON_SCHEMA =
            """{"riskLevel":"LOW|MEDIUM|HIGH","reason":"...","confidence":0.0-1.0,""" +
                """"meeting":{"title":"...","whenText":"...","personName":"..."}|null,""" +
                """"task":{"text":"...","assignee":"..."}|null}"""
    }
}
