package com.audimus.data.tasks

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stage 8: a task/action item captured from a call. */
@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val assignee: String?,
    val sourceCall: String,
    val timestampMs: Long,
)
