package com.audimus.data.calls

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A finished, protected call and its final scam verdict — shown on the dashboard's "Recent calls". */
@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Caller label / transcript source as known at the time. */
    val label: String,
    /** Final risk at the end of the call: "HIGH" | "MEDIUM" | "LOW". */
    val riskLevel: String,
    /** Short reason from the last analysis. */
    val reason: String,
    /** Call length in seconds (0 if unknown). */
    val durationSec: Int,
    val timestampMs: Long,
)
