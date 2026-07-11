package com.audimus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.audimus.data.calendar.CalendarEventDao
import com.audimus.data.calendar.CreatedCalendarEvent
import com.audimus.data.calls.CallRecord
import com.audimus.data.calls.CallRecordDao
import com.audimus.data.tasks.TaskDao
import com.audimus.data.tasks.TaskItem

@Database(
    entities = [TaskItem::class, CreatedCalendarEvent::class, CallRecord::class],
    version = 2,
    exportSchema = false,
)
abstract class AudimusDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        @Volatile
        private var instance: AudimusDatabase? = null

        fun get(context: Context): AudimusDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AudimusDatabase::class.java,
                    "audimus.db",
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
    }
}
