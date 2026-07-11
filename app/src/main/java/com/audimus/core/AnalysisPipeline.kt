package com.audimus.core

import android.content.Context
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
import com.audimus.model.RiskLevel
import com.audimus.stt.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Consumes transcript text (scraped from another app's UI, or typed in the simulator) and runs the
 * on-device analysis: Gemma 4 E4B for scam risk + meeting/task extraction, with a keyword
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
        private const val ANALYSIS_INTERVAL_MS = 4_000L
        private const val TRANSCRIPT_WINDOW_CHARS = 4_000
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
    private val seenMeetings = mutableSetOf<String>()
    private val seenTasks = mutableSetOf<String>()
    private var sourceCall: String = "Live transcript"
    private var dirty = false
    private var initialized = false
    private var callStartMs = 0L
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
        entries.add(TranscriptEntry(System.currentTimeMillis(), clean))
        ProtectionState.setTranscript(entries.toList())
        // Feed the consent handshake: it classifies the caller's spoken yes/no.
        consent.onTranscript(entries.joinToString(" ") { it.text })
        dirty = true
    }

    /** Marks a protected call as started and kicks off the spoken consent handshake. */
    fun beginCall() {
        if (ProtectionState.callActive.value) return
        ProtectionState.setCallActive(true)
        callStartMs = System.currentTimeMillis()
        softStopNotified = false
        consent.start()
    }

    /** Demo entry point: start a call from the dashboard without a live transcript source. */
    fun startSimulatedCall() = beginCall()

    fun grantConsent() = consent.consentManually()
    fun declineConsent() = consent.declineManually()
    fun endCall() = clear()

    fun clear() {
        recordCallIfAny()
        entries.clear()
        seenMeetings.clear()
        seenTasks.clear()
        dirty = false
        callStartMs = 0L
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
                delay(ANALYSIS_INTERVAL_MS)
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
                dirty = false
                // Bound the prompt: reason over the most recent window, not the whole call.
                val text = entries.joinToString(" ") { it.text }.takeLast(TRANSCRIPT_WINDOW_CHARS)
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

    private suspend fun applyAnalysis(result: AnalysisResult) {
        ProtectionState.setLastAnalysis(result)
        ProtectionState.setRisk(result.riskLevel, result.reason, result.confidence)

        when (result.riskLevel) {
            RiskLevel.HIGH -> { ProtectionState.setOverlayVisible(true); vibrate(escalating = true) }
            RiskLevel.MEDIUM -> vibrate(escalating = false)
            RiskLevel.LOW -> ProtectionState.setOverlayVisible(false)
        }

        result.meeting?.let { m ->
            if (seenMeetings.add((m.title + m.whenText).lowercase())) {
                runCatching { calendarRepo.addFromMention(m, sourceCall) }
                    .onFailure { Log.e(TAG, "calendar insert failed", it) }
            }
        }
        result.task?.let { t ->
            if (seenTasks.add(t.text.lowercase())) {
                runCatching { taskRepo.addFromMention(t, sourceCall) }
                    .onFailure { Log.e(TAG, "task insert failed", it) }
            }
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
