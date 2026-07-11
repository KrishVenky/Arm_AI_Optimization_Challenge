package com.audimus.analysis

import com.audimus.model.AnalysisResult
import com.audimus.model.MeetingMention
import com.audimus.model.RiskLevel
import com.audimus.model.TaskMention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Deterministic keyword fallback so the full pipeline is testable before the multi-GB Gemma model
 * is pushed to the device. Reasons only over the whole running transcript with simple heuristics.
 */
class StubCallAnalyzer : CallAnalyzer {

    private val _ready = MutableStateFlow(true)
    override val isReady: StateFlow<Boolean> = _ready

    override suspend fun initialize(): Boolean = true

    override suspend fun analyze(transcript: String): AnalysisResult {
        val t = transcript.lowercase()

        val high = HIGH_RISK.filter { it in t }
        val medium = MEDIUM_RISK.filter { it in t }
        val (risk, reason, conf) = when {
            high.isNotEmpty() -> Triple(
                RiskLevel.HIGH,
                "Detected high-risk request(s): ${high.joinToString()}. Scammers use these to steal money or credentials.",
                0.9f,
            )
            medium.isNotEmpty() -> Triple(
                RiskLevel.MEDIUM,
                "Pressure/urgency language detected: ${medium.joinToString()}.",
                0.6f,
            )
            else -> Triple(RiskLevel.LOW, "No scam indicators so far.", 0.3f)
        }

        return AnalysisResult(
            riskLevel = risk,
            reason = reason,
            confidence = conf,
            meeting = extractMeeting(t),
            task = extractTask(transcript),
        )
    }

    private fun extractMeeting(t: String): MeetingMention? {
        if (MEETING_CUES.none { it in t }) return null
        val whenText = TIME_CUES.firstOrNull { it in t } ?: "unspecified time"
        return MeetingMention(title = "Meeting mentioned on call", whenText = whenText, personName = null)
    }

    private fun extractTask(original: String): TaskMention? {
        val t = original.lowercase()
        if (TASK_CUES.none { it in t }) return null
        return TaskMention(text = original.trim().take(120), assignee = "me")
    }

    override fun close() {}

    private companion object {
        val HIGH_RISK = listOf(
            "otp", "one-time password", "one time password", "gift card", "remote access",
            "social security", "arrest", "warrant", "bank account", "pin number", "wire transfer",
            "bitcoin", "crypto", "routing number", "verification code", "read me the code",
        )
        val MEDIUM_RISK = listOf(
            "urgent", "immediately", "verify your account", "suspended", "act now", "final notice",
            "you owe", "legal action",
        )
        val MEETING_CUES = listOf("meet", "meeting", "appointment", "see you", "let's schedule", "catch up")
        val TIME_CUES = listOf(
            "tomorrow", "today", "tonight", "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "next week", "this week", "at 9", "at 10", "at 11", "at 12",
            "at 1", "at 2", "at 3", "at 4", "at 5", "at 6", "am", "pm",
        )
        val TASK_CUES = listOf("remind", "send me", "can you", "please send", "email me", "call me back", "follow up")
    }
}
