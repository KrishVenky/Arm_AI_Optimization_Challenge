package com.audimus.data.tasks

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(item: TaskItem): Long

    @Query("SELECT * FROM tasks ORDER BY timestampMs DESC")
    fun getAll(): Flow<List<TaskItem>>

    @Delete
    suspend fun delete(item: TaskItem)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int
}
