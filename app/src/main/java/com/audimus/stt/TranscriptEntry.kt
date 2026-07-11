package com.audimus.stt

/** One finalized utterance in the running transcript. */
data class TranscriptEntry(
    val timestampMs: Long,
    val text: String,
)
