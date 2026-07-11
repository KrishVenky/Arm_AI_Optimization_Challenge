package com.audimus.model

/** Risk level for a scam assessment. */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** A proposed meeting extracted from the conversation. */
data class MeetingMention(
    val title: String,
    /** Natural-language date/time as spoken, e.g. "tomorrow at 3pm". */
    val whenText: String,
    /** The other party as named in the conversation (we cannot resolve a real account). */
    val personName: String?,
    /** Resolved epoch-millis start time if parseable, else null. */
    val startMillis: Long? = null,
)

/** A task/action item extracted from the conversation. */
data class TaskMention(
    val text: String,
    /** Assignee as named in conversation (free text), e.g. "me", "John". */
    val assignee: String?,
)

/**
 * One analysis pass over the running transcript window. Produced by [com.audimus.analysis.CallAnalyzer].
 * All fields are best-effort; a pass that yields nothing new leaves the extraction fields null.
 */
data class AnalysisResult(
    val riskLevel: RiskLevel,
    val reason: String,
    val confidence: Float,
    val meeting: MeetingMention? = null,
    val task: TaskMention? = null,
)
