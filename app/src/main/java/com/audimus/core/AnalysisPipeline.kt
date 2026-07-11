package com.audimus.core

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.audimus.analysis.CallAnalyzer
import com.audimus.analysis.GemmaCallAnalyzer
import com.audimus.analysis.StubCallAnalyzer
import com.audimus.data.calendar.CalendarRepository
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
    }

    private val calendarRepo = CalendarRepository(context)
    private val taskRepo = TaskRepository(context)
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

    suspend fun initialize() {
        if (initialized) return
        initialized = true

        val gemma = GemmaCallAnalyzer(context)
        if (gemma.initialize()) {
            analyzer = gemma
            ProtectionState.setUsingGemma(true)
            ProtectionState.setStatusLine("Gemma 4 E4B ready")
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
        entries.add(TranscriptEntry(System.currentTimeMillis(), clean))
        ProtectionState.setTranscript(entries.toList())
        dirty = true
    }

    fun clear() {
        entries.clear()
        seenMeetings.clear()
        seenTasks.clear()
        dirty = false
        ProtectionState.clearSession()
    }

    private fun startAnalysisLoop() {
        scope.launch {
            while (isActive) {
                delay(ANALYSIS_INTERVAL_MS)
                if (!dirty) continue
                dirty = false
                val text = entries.joinToString(" ") { it.text }
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
        runCatching { analyzer.close() }
    }
}
