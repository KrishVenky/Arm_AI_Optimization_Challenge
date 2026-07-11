package com.audimus.data.calendar

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {
    @Insert
    suspend fun insert(item: CreatedCalendarEvent): Long

    @Query("SELECT * FROM calendar_events ORDER BY createdAtMillis DESC")
    fun getAll(): Flow<List<CreatedCalendarEvent>>

    @Delete
    suspend fun delete(item: CreatedCalendarEvent)
}
