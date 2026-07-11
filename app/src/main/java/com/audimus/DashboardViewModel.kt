package com.audimus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audimus.data.calendar.CalendarRepository
import com.audimus.data.calendar.CreatedCalendarEvent
import com.audimus.data.calls.CallRecord
import com.audimus.data.calls.CallRepository
import com.audimus.data.tasks.TaskItem
import com.audimus.data.tasks.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Read-only dashboard state: the app-created calendar events and tasks. */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val taskRepo = TaskRepository(app)
    private val calendarRepo = CalendarRepository(app)
    private val callRepo = CallRepository(app)

    val recentCalls: StateFlow<List<CallRecord>> =
        callRepo.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<TaskItem>> =
        taskRepo.tasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<CreatedCalendarEvent>> =
        calendarRepo.events().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
