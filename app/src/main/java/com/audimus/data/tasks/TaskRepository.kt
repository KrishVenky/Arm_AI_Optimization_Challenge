package com.audimus.data.tasks

import android.content.Context
import com.audimus.data.AudimusDatabase
import com.audimus.model.TaskMention
import kotlinx.coroutines.flow.Flow

/** Stage 8 repository over the Room task table. */
class TaskRepository(private val dao: TaskDao) {

    constructor(context: Context) : this(AudimusDatabase.get(context).taskDao())

    fun tasks(): Flow<List<TaskItem>> = dao.getAll()

    suspend fun addFromMention(mention: TaskMention, sourceCall: String): Long =
        dao.insert(
            TaskItem(
                text = mention.text,
                assignee = mention.assignee,
                sourceCall = sourceCall,
                timestampMs = System.currentTimeMillis(),
            ),
        )

    suspend fun delete(item: TaskItem) = dao.delete(item)

    suspend fun count(): Int = dao.count()
}
