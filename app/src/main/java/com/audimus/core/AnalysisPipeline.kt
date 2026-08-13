package com.audimus.core

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.audimus.analysis.CallAnalyzer
import com.audimus.analysis.GemmaCallAnalyzer
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
        /** Poll cheaply; actual model work is gated by new-text and timing thresholds below. */
        private const val ANALYSIS_POLL_MS = 1_000L
        /** Avoid launching expensive inference repeatedly during fast caption updates. */
        private const val MIN_ANALYSIS_INTERVAL_MS = 4_000L
        /** Analyze once this much genuinely new text has arrived. */
        private const val MIN_NEW_CHARS_FOR_ANALYSIS = 160
        /** Do not leave a quiet, short exchange unanalysed indefinitely. */
        private const val MAX_ANALYSIS_DELAY_MS = 10_000L
        private const val TRANSCRIPT_WINDOW_CHARS = 4_000
        /** Bound UI and in-memory transcript retention during a long call. */
        private const val TRANSCRIPT_BUFFER_CHARS = 6_000
        /** No new transcript for this long (after consent) auto-ends the call → review sheet. */
        private const val CALL_IDLE_END_MS = 9_000L
        private val HIGH_RISK_SIGNALS = listOf(
            "one-time password", "one time password", "verification code", "read me the code",
            "gift card", "remote access", "wire transfer", "pin number", "otp",
        )
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
    private var newCharsSinceAnalysis = 0
    private var lastAnalysisAtMs = 0L
    private var forceNextAnalysis = false
    /** Increments for each new caption chunk; prevents stale model results replacing newer risk. */
    private var transcriptRevision = 0L
    private var initialized = false
    private var callStartMs = 0L
    private var lastUtteranceMs = 0L
    private var softStopNotified = false

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
        transcriptRevision++
        trimTranscriptBuffer()
        ProtectionState.setTranscript(entries.toList())
        // Feed the consent handshake: it classifies the caller's spoken yes/no.
        consent.onTranscript(entries.joinToString(" ") { it.text })
        dirty = true
        newCharsSinceAnalysis += clean.length + 1
        applyImmediateRiskRule(clean)
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
        transcriptRevision++ // invalidate any result from a preceding call session
        callStartMs = System.currentTimeMillis()
        lastUtteranceMs = callStartMs
        softStopNotified = false
        newCharsSinceAnalysis = 0
        lastAnalysisAtMs = 0L
        forceNextAnalysis = false
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
        transcriptRevision++ // an in-flight model pass must not update an ended call
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
        transcriptRevision++ // invalidate any in-flight model pass before clearing the session
        entries.clear()
        pendingMeetings.clear()
        pendingTasks.clear()
        followupIds = 0L
        dirty = false
        newCharsSinceAnalysis = 0
        lastAnalysisAtMs = 0L
        forceNextAnalysis = false
        callStartMs = 0L
        lastUtteranceMs = 0L
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
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastAnalysisAtMs
                val enoughNewText = newCharsSinceAnalysis >= MIN_NEW_CHARS_FOR_ANALYSIS
                val waitedLongEnough = elapsed >= MAX_ANALYSIS_DELAY_MS
                if (!forceNextAnalysis && (elapsed < MIN_ANALYSIS_INTERVAL_MS || (!enoughNewText && !waitedLongEnough))) {
                    continue
                }
                dirty = false
                newCharsSinceAnalysis = 0
                forceNextAnalysis = false
                lastAnalysisAtMs = now
                // Bound the prompt: reason over the most recent window, not the whole call.
                val text = entries.joinToString(" ") { it.text }.takeLast(TRANSCRIPT_WINDOW_CHARS)
                if (text.isBlank()) continue
                val revisionAtStart = transcriptRevision
                val startedAt = SystemClock.elapsedRealtime()
                val result = try {
                    analyzer.analyze(text)
                } catch (e: Exception) {
                    Log.e(TAG, "analysis failed", e); null
                } ?: continue
                Log.i(
                    TAG,
                    "analysis complete in ${SystemClock.elapsedRealtime() - startedAt}ms " +
                        "(promptChars=${text.length}, retainedChars=${entries.sumOf { it.text.length }})",
                )
                if (revisionAtStart != transcriptRevision) {
                    Log.i(TAG, "Discarding stale analysis result (revision=$revisionAtStart, current=$transcriptRevision)")
                    continue
                }
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

    /**
     * A tiny, deterministic fast path for unequivocal scam requests. It runs on each caption
     * chunk so the user gets an immediate warning while the slower Gemma pass gathers context
     * and replaces this provisional reason with a richer result.
     */
    private fun applyImmediateRiskRule(text: String) {
        if (ProtectionState.consentState.value != ConsentState.CONSENTED) return
        val matched = HIGH_RISK_SIGNALS.firstOrNull { it in text.lowercase() } ?: return
        forceNextAnalysis = true
        ProtectionState.setRisk(
            RiskLevel.HIGH,
            "Potential scam signal detected: $matched. Do not share codes, PINs, or payment details.",
            0.95f,
        )
        ProtectionState.setOverlayVisible(true)
        vibrate(escalating = true)
        Log.i(TAG, "Immediate high-risk rule matched: $matched")
    }

    /** Remove oldest lines after publishing a recent, UI-friendly rolling transcript buffer. */
    private fun trimTranscriptBuffer() {
        var retainedChars = entries.sumOf { it.text.length + 1 }
        while (retainedChars > TRANSCRIPT_BUFFER_CHARS && entries.size > 1) {
            retainedChars -= entries.removeAt(0).text.length + 1
        }
    }

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
