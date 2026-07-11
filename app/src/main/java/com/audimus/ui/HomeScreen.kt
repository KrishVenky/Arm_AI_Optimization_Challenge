package com.audimus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audimus.DashboardViewModel
import com.audimus.core.AudimusBridge
import com.audimus.core.ProtectionState
import com.audimus.data.calendar.CreatedCalendarEvent
import com.audimus.data.tasks.TaskItem
import com.audimus.model.RiskLevel
import com.audimus.stt.TranscriptEntry
import com.audimus.ui.theme.RiskHigh
import com.audimus.ui.theme.RiskMedium
import com.audimus.ui.theme.SafeGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)

private enum class Tab(val label: String, val glyph: String) {
    PROTECTION("Protection", "🛡"),
    CALENDAR("Calendar", "📅"),
    TASKS("Tasks", "✓"),
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(Tab.PROTECTION) }
    val vm: DashboardViewModel = viewModel()

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.glyph) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.PROTECTION -> ProtectionTab()
                Tab.CALENDAR -> CalendarTab(vm)
                Tab.TASKS -> TasksTab(vm)
            }
        }
    }
}

@Composable
private fun ProtectionTab() {
    val serviceConnected by ProtectionState.serviceConnected.collectAsStateWithLifecycle()
    val sourceApp by ProtectionState.sourceApp.collectAsStateWithLifecycle()
    val scraping by ProtectionState.scraping.collectAsStateWithLifecycle()
    val transcript by ProtectionState.transcript.collectAsStateWithLifecycle()
    val risk by ProtectionState.riskLevel.collectAsStateWithLifecycle()
    val riskReason by ProtectionState.riskReason.collectAsStateWithLifecycle()
    val riskConf by ProtectionState.riskConfidence.collectAsStateWithLifecycle()
    val status by ProtectionState.statusLine.collectAsStateWithLifecycle()
    val analyzerReady by ProtectionState.analyzerReady.collectAsStateWithLifecycle()
    val usingGemma by ProtectionState.usingGemma.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Audimus",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        SectionCard("Protection") {
            StatusRow(
                if (serviceConnected) SafeGreen else RiskMedium,
                if (serviceConnected) "Accessibility service running" else "Service not connected — enable it in Settings",
            )
            StatusRow(
                if (scraping) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                sourceApp?.let { "Reading transcript from $it" } ?: "No transcript source on screen",
            )
            Text(
                "Analyzer: " + when {
                    !analyzerReady -> "loading…"
                    usingGemma -> "Gemma 4 E4B (on-device)"
                    else -> "keyword fallback (push the Gemma model for full reasoning)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        RiskCard(risk, riskReason, riskConf)

        SimulatorCard()

        SectionCard("Live transcript") {
            if (transcript.isEmpty()) {
                Text(
                    "Nothing yet. Turn on live captions / a transcription app during a call, or use the simulator above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                transcript.takeLast(40).forEach { TranscriptLine(it) }
            }
        }
    }
}

@Composable
private fun SimulatorCard() {
    var input by remember { mutableStateOf("") }
    SectionCard("Simulator (demo)") {
        Text(
            "Feed a line of call transcript to the on-device analyzer, as if it had been scraped from a captioning app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Transcript line") },
            placeholder = { Text("e.g. This is the IRS, read me the code we just sent…") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        AudimusBridge.pipeline?.appendUtterance(text)
                        input = ""
                    }
                },
            ) { Text("Send") }
            OutlinedButton(onClick = { AudimusBridge.pipeline?.clear() }) { Text("Clear call") }
        }
        if (AudimusBridge.pipeline == null) {
            Text(
                "Enable the accessibility service first so the analyzer is running.",
                style = MaterialTheme.typography.bodySmall, color = RiskMedium,
            )
        }
    }
}

@Composable
private fun RiskCard(risk: RiskLevel, reason: String, confidence: Float) {
    val color = when (risk) {
        RiskLevel.LOW -> SafeGreen
        RiskLevel.MEDIUM -> RiskMedium
        RiskLevel.HIGH -> RiskHigh
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(14.dp).background(color, CircleShape))
                Text(
                    "Risk: ${risk.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = color,
                )
                if (confidence > 0f) {
                    Text(
                        "(${(confidence * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TranscriptLine(entry: TranscriptEntry) {
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            timeFmt.format(Date(entry.timestampMs)),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        )
        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CalendarTab(vm: DashboardViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    TabScaffold(
        "Meetings from calls", events.isEmpty(),
        "No meetings captured yet. When a caller proposes a meeting, Audimus adds it to your device calendar.",
    ) { items(events) { EventRow(it) } }
}

@Composable
private fun TasksTab(vm: DashboardViewModel) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    TabScaffold(
        "Tasks from calls", tasks.isEmpty(),
        "No tasks captured yet. Explicit action items mentioned on a call show up here.",
    ) { items(tasks) { TaskRow(it) } }
}

@Composable
private fun TabScaffold(
    title: String,
    empty: Boolean,
    emptyText: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        )
        if (empty) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
private fun EventRow(e: CreatedCalendarEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(dateFmt.format(Date(e.startMillis)), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            e.personName?.let { Text("With $it", style = MaterialTheme.typography.bodyMedium) }
            Text("From: ${e.sourceCall}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (e.calendarEventId < 0) {
                Text("(not written to device calendar)", style = MaterialTheme.typography.bodySmall,
                    color = RiskMedium)
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.text, style = MaterialTheme.typography.titleMedium)
            t.assignee?.let {
                Text("For: $it", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("From: ${t.sourceCall} · ${dateFmt.format(Date(t.timestampMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun StatusRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
