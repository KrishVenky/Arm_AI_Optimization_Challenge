package com.audimus.analysis

import com.audimus.model.RiskLevel

/**
 * Deterministic, synchronous keyword check — the same word lists [StubCallAnalyzer] uses as its
 * full fallback analyzer, exposed standalone so [com.audimus.core.AnalysisPipeline] can run it
 * instantly on every new transcript chunk (no coroutine, no model, sub-millisecond) instead of
 * waiting for the next Gemma pass. Gemma still runs on its normal cadence afterward and can
 * confirm, refine the reason, or (per [com.audimus.core.AnalysisPipeline]'s de-escalation logic)
 * downgrade a provisional flag that turns out to be a false trigger.
 */
object RuleBasedRiskDetector {

    data class Hit(val riskLevel: RiskLevel, val reason: String, val confidence: Float)

    /** Returns a HIGH/MEDIUM hit if [transcript] contains a known trigger phrase, else null (LOW
     *  is the default absence of a hit — this detector never asserts LOW, only escalates). */
    fun check(transcript: String): Hit? {
        val t = transcript.lowercase()
        val high = HIGH_RISK.filter { it in t }
        if (high.isNotEmpty()) {
            return Hit(
                RiskLevel.HIGH,
                "Detected high-risk request(s): ${high.joinToString()}. Scammers use these to steal money or credentials.",
                0.9f,
            )
        }
        val medium = MEDIUM_RISK.filter { it in t }
        if (medium.isNotEmpty()) {
            return Hit(RiskLevel.MEDIUM, "Pressure/urgency language detected: ${medium.joinToString()}.", 0.6f)
        }
        return null
    }

    val HIGH_RISK = listOf(
        "otp", "one-time password", "one time password", "gift card", "remote access",
        "social security", "arrest", "warrant", "bank account", "pin number", "wire transfer",
        "bitcoin", "crypto", "routing number", "verification code", "read me the code",
    )
    val MEDIUM_RISK = listOf(
        "urgent", "immediately", "verify your account", "suspended", "act now", "final notice",
        "you owe", "legal action",
    )
}
