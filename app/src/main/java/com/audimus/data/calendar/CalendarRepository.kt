package com.audimus.data.calendar

import android.content.Context
import com.audimus.data.AudimusDatabase
import com.audimus.model.MeetingMention
import kotlinx.coroutines.flow.Flow

/** Stage 7 repository: inserts into the device calendar and keeps a local record of app-created events. */
class CalendarRepository(context: Context) {

    private val dao = AudimusDatabase.get(context).calendarEventDao()
    private val writer = CalendarWriter(context)

    fun events(): Flow<List<CreatedCalendarEvent>> = dao.getAll()

    suspend fun addFromMention(meeting: MeetingMention, sourceCall: String): Long {
        val start = meeting.startMillis ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
        val eventId = writer.insertMeeting(meeting, sourceCall)
        dao.insert(
            CreatedCalendarEvent(
                calendarEventId = eventId,
                title = meeting.title,
                whenText = meeting.whenText,
                personName = meeting.personName,
                startMillis = start,
                sourceCall = sourceCall,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return eventId
    }
}
