package com.audimus.data.calendar

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stage 7: a local record of a calendar event Audimus created, so the app can list its own events. */
@Entity(tableName = "calendar_events")
data class CreatedCalendarEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The CalendarContract.Events _ID, or -1 if the insert into the device calendar failed. */
    val calendarEventId: Long,
    val title: String,
    val whenText: String,
    val personName: String?,
    val startMillis: Long,
    val sourceCall: String,
    val createdAtMillis: Long,
)
