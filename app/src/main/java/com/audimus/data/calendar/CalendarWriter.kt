package com.audimus.data.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.audimus.model.MeetingMention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Stage 7: inserts real events into the device's LOCAL calendar via [CalendarContract].
 *
 * No Google account and no network required — it targets a locally-owned calendar
 * ([CalendarContract.ACCOUNT_TYPE_LOCAL]), creating one named "Audimus" if the device has no
 * writable calendar yet. Requires READ_CALENDAR / WRITE_CALENDAR (declared in the manifest).
 */
class CalendarWriter(private val context: Context) {

    companion object {
        private const val TAG = "CalendarWriter"
        private const val ACCOUNT_NAME = "Audimus"
        private const val CALENDAR_NAME = "Audimus"
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
        private const val ONE_DAY_MS = 24 * ONE_HOUR_MS
    }

    /** Inserts a meeting; returns the CalendarContract event _ID, or -1 on failure. Never throws. */
    suspend fun insertMeeting(meeting: MeetingMention, sourceCall: String): Long =
        withContext(Dispatchers.IO) {
            try {
                val calendarId = findOrCreateLocalCalendar() ?: return@withContext -1L
                val start = meeting.startMillis ?: (System.currentTimeMillis() + ONE_DAY_MS)
                val end = start + ONE_HOUR_MS
                val tz = TimeZone.getDefault().id

                val description = buildString {
                    if (!meeting.personName.isNullOrBlank()) {
                        append("With: ${meeting.personName} (as named on the call). ")
                    }
                    append("When (as spoken): ${meeting.whenText}. ")
                    append("Created by Audimus from call: $sourceCall.")
                }

                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, end)
                    put(CalendarContract.Events.TITLE, meeting.title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                val id = uri?.let { ContentUris.parseId(it) } ?: -1L
                Log.i(TAG, "Inserted meeting '${meeting.title}' into calendar $calendarId as event $id")
                id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert meeting", e)
                -1L
            }
        }

    /** Prefer a local writable calendar; else any writable one; else create a local "Audimus" calendar. */
    private fun findOrCreateLocalCalendar(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val typeCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val accessCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            var firstWritable: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val type = cursor.getString(typeCol)
                val access = cursor.getInt(accessCol)
                val writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                if (!writable) continue
                if (firstWritable == null) firstWritable = id
                if (type == CalendarContract.ACCOUNT_TYPE_LOCAL) return id // best match
            }
            if (firstWritable != null) return firstWritable
        }
        return createLocalCalendar()
    }

    private fun createLocalCalendar(): Long? {
        return try {
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.ACCOUNT_TYPE_LOCAL,
                )
                .build()
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF0B4F4A.toInt())
                put(
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_OWNER,
                )
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            }
            val result = context.contentResolver.insert(uri, values)
            result?.let { ContentUris.parseId(it) }.also {
                Log.i(TAG, "Created local Audimus calendar id=$it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local calendar", e)
            null
        }
    }
}
