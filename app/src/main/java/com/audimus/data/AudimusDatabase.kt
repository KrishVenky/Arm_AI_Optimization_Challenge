package com.audimus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.audimus.data.calendar.CalendarEventDao
import com.audimus.data.calendar.CreatedCalendarEvent
import com.audimus.data.tasks.TaskDao
import com.audimus.data.tasks.TaskItem

@Database(
    entities = [TaskItem::class, CreatedCalendarEvent::class],
    version = 1,
    exportSchema = false,
)
abstract class AudimusDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        @Volatile
        private var instance: AudimusDatabase? = null

        fun get(context: Context): AudimusDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AudimusDatabase::class.java,
                    "audimus.db",
                ).build().also { instance = it }
            }
    }
}
