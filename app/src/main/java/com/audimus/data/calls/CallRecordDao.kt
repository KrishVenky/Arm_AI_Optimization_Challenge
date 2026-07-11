package com.audimus.data.calls

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Insert
    suspend fun insert(record: CallRecord): Long

    @Query("SELECT * FROM call_records ORDER BY timestampMs DESC LIMIT 30")
    fun recent(): Flow<List<CallRecord>>
}
