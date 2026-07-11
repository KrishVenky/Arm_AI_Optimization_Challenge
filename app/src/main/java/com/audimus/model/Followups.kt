package com.audimus.model

/**
 * Editable follow-ups detected on a call, held in memory until the user reviews them at call-end and
 * confirms. Nothing is written to the calendar/tasks until confirmation — this is what prevents the
 * "one event per re-detection" duplication (a meeting whose date is revised mid-call updates its
 * single entry rather than inserting a new one).
 */
data class PendingMeeting(
    val id: Long,
    val title: String,
    val whenText: String,
    val person: String?,
)

data class PendingTask(
    val id: Long,
    val text: String,
    val assignee: String?,
)
