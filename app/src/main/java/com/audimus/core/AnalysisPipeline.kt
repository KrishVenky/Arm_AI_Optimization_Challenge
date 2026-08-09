package com.audimus.core

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.audimus.analysis.CallAnalyzer
import com.audimus.analysis.GemmaCallAnalyzer
import com.audimus.analysis.RuleBasedRiskDetector
import com.audimus.analysis.StubCallAnalyzer
import com.audimus.consent.ConsentController
import com.audimus.consent.ConsentState
import com.audimus.data.calendar.CalendarRepository
import com.audimus.data.calls.CallRepository
import com.audimus.data.tasks.TaskRepository
import com.audimus.model.AnalysisResult
import com.audimus.model.MeetingMention
import com.audimus.model.PendingMeeting
import com.audimus.model.PendingTask
import com.audimus.model.RiskLevel
import com.audimus.model.TaskMention
import com.audimus.stt.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Consumes transcript text (scraped from another app's UI, or typed in the simulator) and runs the
 * on-device analysis: Gemma 4 E2B for scam risk + meeting/task extraction, with a keyword
 * [StubCallAnalyzer] fallback until the model is present. Mirrors results into [ProtectionState] and
 * drives vibration, the risk overlay, and calendar/task persistence.
 *
 * No audio anywhere: the pipeline's input is already text.
 */
class AnalysisPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AnalysisPipeline"
        /** How often the loop checks whether a Gemma pass is warranted -- cheap, no model call. */
        private const val ANALYSIS_POLL_MS = 1_500L
        /** A Gemma pass fires once this many new characters have arrived since the last pass... */
        private const val MIN_NEW_CHARS_FOR_ANALYSIS = 150
        /** ...or this long has passed since the last pass, whichever comes first -- so a call with
         *  only a trickle of new text still gets analyzed instead of stalling. */
        private const val MAX_ANALYSIS_WAIT_MS = 10_000L
        private const val TRANSCRIPT_WINDOW_CHARS = 4_000
        /** entries is trimmed from the front once it exceeds this, so a long call's per-utterance
         *  joins (consent classification, UI transcript) stay bounded instead of growing for the
         *  whole call duration. Kept larger than TRANSCRIPT_WINDOW_CHARS so the Gemma prompt window
         *  is never starved by the trim. */
        private const val MAX_ENTRIES_CHARS = TRANSCRIPT_WINDOW_CHARS * 2
        /** No new transcript for this long (after consent) auto-ends the call → review sheet. */
        private const val CALL_IDLE_END_MS = 9_000L
    }

    private val calendarRepo = CalendarRepository(context)
    private val taskRepo = TaskRepository(context)
    private val callRepo = CallRepository(context)
    private val consent = ConsentController(context, scope)
    private val vibrator by lazy {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    }

    private lateinit var analyzer: CallAnalyzer
    private val entries = mutableListOf<TranscriptEntry>()
    // Detected follow-ups accumulate here (upserted by title/text), and are only written to the
    // calendar/tasks when the user confirms at call-end — so re-detections update, never duplicate.
    private val pendingMeetings = mutableListOf<PendingMeeting>()
    private val pendingTasks = mutableListOf<PendingTask>()
    private var followupIds = 0L
    private var sourceCall: String = "Live transcript"
    private var dirty = false
    private var initialized = false
    private var callStartMs = 0L
    private var lastUtteranceMs = 0L
    private var softStopNotified = false
    private var charsAtLastAnalysis = 0
    private var lastAnalysisMs = 0L
    /** Set by an instant rule-based hit so the next poll runs Gemma right away instead of waiting
     *  out the normal batching window -- the fast path flags it, Gemma confirms/refines/de-escalates. */
    private var instantTriggerPending = false

    suspend fun initialize() {
        if (initialized) return
        initialized = true

        val gemma = GemmaCallAnalyzer(context)
        if (gemma.initialize()) {
            analyzer = gemma
            ProtectionState.setUsingGemma(true)
            ProtectionState.setStatusLine("Gemma 4 E2B ready")
        } else {
            analyzer = StubCallAnalyzer().also { it.initialize() }
            ProtectionState.setUsingGemma(false)
            ProtectionState.setStatusLine("Using keyword analyzer (push the Gemma model for full reasoning)")
        }
        ProtectionState.setAnalyzerReady(true)
        startAnalysisLoop()
        startCallEndWatchdog()
        Log.i(TAG, "Analysis pipeline ready (gemma=${ProtectionState.usingGemma.value})")
    }

    /** Set the label recorded on extracted meetings/tasks (e.g. the caller or the source app). */
    fun setSource(label: String) { sourceCall = label }

    /** Append a finalized transcript line. Ignores exact repeats of the previous line. */
    fun appendUtterance(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (entries.lastOrNull()?.text == clean) return
        if (!ProtectionState.callActive.value) beginCall()
        lastUtteranceMs = System.currentTimeMillis()
        entries.add(TranscriptEntry(lastUtteranceMs, clean))
        trimEntries()
        ProtectionState.setTranscript(entries.toList())
        // Feed the consent handshake: it classifies the caller's spoken yes/no.
        consent.onTranscript(entries.joinToString(" ") { it.text })
        dirty = true

        // Instant deterministic check on just the new line: don't wait for the next batched Gemma
        // pass to flag an obvious hard trigger (OTP/PIN/gift card/etc). Gemma still runs afterward
        // (instantTriggerPending pulls its next pass forward) and can refine the reason or, if this
        // was a false trigger, de-escalate via its own applyAnalysis pass.
        if (ProtectionState.consentState.value == ConsentState.CONSENTED) {
            RuleBasedRiskDetector.check(clean)?.let { hit ->
                instantTriggerPending = true
                if (hit.riskLevel.ordinal > ProtectionState.riskLevel.value.ordinal) {
                    applyInstantHit(hit)
                }
            }
        }
    }

    /** entries is unbounded for a call's whole duration otherwise; trim old lines once the total
     *  text exceeds MAX_ENTRIES_CHARS so per-utterance joins (consent, UI transcript) stay bounded
     *  instead of growing for the rest of a long call. */
    private fun trimEntries() {
        var total = entries.sumOf { it.text.length }
        while (total > MAX_ENTRIES_CHARS && entries.size > 1) {
            total -= entries.removeAt(0).text.length
        }
    }

    private fun applyInstantHit(hit: RuleBasedRiskDetector.Hit) {
        ProtectionState.setRisk(hit.riskLevel, "${hit.reason} (instant check, confirming)", hit.confidence)
        when (hit.riskLevel) {
            RiskLevel.HIGH -> { ProtectionState.setOverlayVisible(true); vibrate(escalating = true) }
            RiskLevel.MEDIUM -> vibrate(escalating = false)
            RiskLevel.LOW -> {}
        }
    }

    /** Marks a protected call as started and kicks off the spoken consent handshake. */
    fun beginCall() {
        if (ProtectionState.callActive.value) return
        pendingMeetings.clear()
        pendingTasks.clear()
        followupIds = 0L
        ProtectionState.setPendingMeetings(emptyList())
        ProtectionState.setPendingTasks(emptyList())
        ProtectionState.setShowFollowups(false)
        ProtectionState.setCallActive(true)
        callStartMs = System.currentTimeMillis()
        lastUtteranceMs = callStartMs
        lastAnalysisMs = callStartMs
        charsAtLastAnalysis = 0
        instantTriggerPending = false
        softStopNotified = false
        consent.start()
    }

    /** Demo entry point: start a call from the dashboard without a live transcript source. */
    fun startSimulatedCall() = beginCall()

    fun grantConsent() = consent.consentManually()
    fun declineConsent() = consent.declineManually()

    /**
     * End the current call. Records the call, then — if any follow-ups were detected — shows the
     * review sheet (nothing is written yet). Otherwise resets straight away.
     */
    fun endCall() {
        if (!ProtectionState.callActive.value) { resetAll(); return }
        recordCallIfAny()
        consent.reset()
        ProtectionState.setCallActive(false)
        ProtectionState.setScraping(false)
        if (pendingMeetings.isNotEmpty() || pendingTasks.isNotEmpty()) {
            // Keep the pending follow-ups (already mirrored to ProtectionState) for review.
            entries.clear()
            dirty = false
            ProtectionState.setShowFollowups(true)
        } else {
            resetAll()
        }
    }

    /** "Clear call" button — discards everything without a review. */
    fun clear() = resetAll()

    /** User confirmed the (possibly edited) follow-ups: write them to the calendar + tasks. */
    fun confirmFollowups() {
        val meetings = ProtectionState.pendingMeetings.value
        val tasks = ProtectionState.pendingTasks.value
        val label = sourceCall
        scope.launch {
            meetings.forEach { m ->
                runCatching {
                    calendarRepo.addFromMention(
                        MeetingMention(title = m.title, whenText = m.whenText, personName = m.person),
                        label,
                    )
                }.onFailure { Log.e(TAG, "calendar insert failed", it) }
            }
            tasks.forEach { t ->
                runCatching {
                    taskRepo.addFromMention(TaskMention(text = t.text, assignee = t.assignee), label)
                }.onFailure { Log.e(TAG, "task insert failed", it) }
            }
        }
        resetAll()
    }

    /** User skipped the review: keep nothing. */
    fun dismissFollowups() = resetAll()

    private fun resetAll() {
        entries.clear()
        pendingMeetings.clear()
        pendingTasks.clear()
        followupIds = 0L
        dirty = false
        callStartMs = 0L
        lastUtteranceMs = 0L
        lastAnalysisMs = 0L
        charsAtLastAnalysis = 0
        instantTriggerPending = false
        softStopNotified = false
        consent.reset()
        ProtectionState.clearSession()
    }

    /** Log a finished call (label + final verdict + duration) for the dashboard's recent list. */
    private fun recordCallIfAny() {
        if (!ProtectionState.callActive.value || entries.isEmpty()) return
        val durationSec = if (callStartMs > 0) ((System.currentTimeMillis() - callStartMs) / 1000).toInt() else 0
        val risk = ProtectionState.riskLevel.value
        val reason = ProtectionState.riskReason.value.ifBlank { "No scam indicators detected." }
        val label = sourceCall
        scope.launch { runCatching { callRepo.record(label, risk, reason, durationSec) } }
    }

    private fun startAnalysisLoop() {
        scope.launch {
            while (isActive) {
                delay(ANALYSIS_POLL_MS)
                // Gate on the consent handshake: no scam analysis until the caller agrees.
                when (ProtectionState.consentState.value) {
                    ConsentState.IDLE, ConsentState.REQUESTING -> continue
                    ConsentState.DECLINED, ConsentState.NO_RESPONSE -> {
                        if (!softStopNotified) {
                            softStopNotified = true
                            ProtectionState.setStatusLine("Protection paused — caller did not consent")
                        }
                        continue
                    }
                    ConsentState.CONSENTED -> { /* proceed */ }
                }
                if (!dirty) continue
                val fullText = entries.joinToString(" ") { it.text }
                val newChars = fullText.length - charsAtLastAnalysis
                val waitedLongEnough = System.currentTimeMillis() - lastAnalysisMs >= MAX_ANALYSIS_WAIT_MS
                // Batch Gemma calls: a full pass every time ANY new word arrives burns heat/battery
                // for no benefit when the new text is a few characters. Wait for a meaningful chunk
                // of new text, a hard rule-based trigger, or a wait-floor so slow trickling speech
                // still gets analyzed eventually.
                val shouldAnalyze = instantTriggerPending || newChars >= MIN_NEW_CHARS_FOR_ANALYSIS || waitedLongEnough
                if (!shouldAnalyze) continue
                dirty = false
                instantTriggerPending = false
                charsAtLastAnalysis = fullText.length
                lastAnalysisMs = System.currentTimeMillis()
                // Bound the prompt: reason over the most recent window, not the whole call.
                val text = fullText.takeLast(TRANSCRIPT_WINDOW_CHARS)
                if (text.isBlank()) continue
                val result = try {
                    analyzer.analyze(text)
                } catch (e: Exception) {
                    Log.e(TAG, "analysis failed", e); null
                } ?: continue
                applyAnalysis(result)
            }
        }
    }

    /** Auto-ends the call (→ review sheet) once captions stop arriving for a while. */
    private fun startCallEndWatchdog() {
        scope.launch {
            while (isActive) {
                delay(2_000L)
                val active = ProtectionState.callActive.value
                val consented = ProtectionState.consentState.value == ConsentState.CONSENTED
                val idleFor = System.currentTimeMillis() - lastUtteranceMs
                if (active && consented && entries.isNotEmpty() && idleFor > CALL_IDLE_END_MS) {
                    Log.i(TAG, "Call idle ${idleFor}ms — auto-ending and prompting follow-ups")
                    endCall()
                }
            }
        }
    }

    private fun applyAnalysis(result: AnalysisResult) {
        ProtectionState.setLastAnalysis(result)
        ProtectionState.setRisk(result.riskLevel, result.reason, result.confidence)

        when (result.riskLevel) {
            RiskLevel.HIGH -> { ProtectionState.setOverlayVisible(true); vibrate(escalating = true) }
            RiskLevel.MEDIUM -> vibrate(escalating = false)
            RiskLevel.LOW -> ProtectionState.setOverlayVisible(false)
        }

        // Upsert follow-ups by normalized title/text: a re-detection (e.g. Monday → Tuesday) UPDATES
        // the existing entry instead of adding a new one. Written to calendar/tasks only on confirm.
        result.meeting?.let { m ->
            val key = normalize(m.title)
            val idx = pendingMeetings.indexOfFirst { normalize(it.title) == key }
            val entry = PendingMeeting(
                id = if (idx >= 0) pendingMeetings[idx].id else followupIds++,
                title = m.title,
                whenText = m.whenText,
                person = m.personName,
            )
            if (idx >= 0) pendingMeetings[idx] = entry else pendingMeetings.add(entry)
            ProtectionState.setPendingMeetings(pendingMeetings.toList())
        }
        result.task?.let { t ->
            val key = normalize(t.text)
            if (pendingTasks.none { normalize(it.text) == key }) {
                pendingTasks.add(PendingTask(id = followupIds++, text = t.text, assignee = t.assignee))
                ProtectionState.setPendingTasks(pendingTasks.toList())
            }
        }
    }

    private fun normalize(s: String): String = s.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun vibrate(escalating: Boolean) {
        try {
            val effect = if (escalating) {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 150, 400, 150, 600), intArrayOf(0, 120, 0, 180, 0, 255), -1,
                )
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 200, 200, 200), -1)
            }
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    fun dismissOverlay() = ProtectionState.setOverlayVisible(false)

    fun shutdown() {
        runCatching { consent.shutdown() }
        runCatching { analyzer.close() }
    }
}
