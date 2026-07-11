package com.audimus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audimus.DashboardViewModel
import com.audimus.consent.ConsentState
import com.audimus.core.AudimusBridge
import com.audimus.core.ProtectionState
import com.audimus.data.calendar.CreatedCalendarEvent
import com.audimus.data.calls.CallRecord
import com.audimus.data.tasks.TaskItem
import com.audimus.model.RiskLevel
import com.audimus.stt.TranscriptEntry
import com.audimus.ui.theme.AudimusTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private val clockFmt = SimpleDateFormat("h:mm a", Locale.US)
private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)

private enum class Tab(val label: String, val glyph: String) {
    PROTECTION("Protection", "🛡"),
    CALENDAR("Calendar", "📅"),
    TASKS("Tasks", "✓"),
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val vm: DashboardViewModel = viewModel()
    var tab by remember { mutableStateOf(Tab.PROTECTION) }

    val callActive by ProtectionState.callActive.collectAsStateWithLifecycle()
    val consent by ProtectionState.consentState.collectAsStateWithLifecycle()
    val aud = AudimusTheme.colors

    Box(modifier.fillMaxSize().background(aud.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.PROTECTION -> if (callActive) ActiveCallView() else DashboardView(vm)
                    Tab.CALENDAR -> CalendarTab(vm, callActive) { tab = Tab.PROTECTION }
                    Tab.TASKS -> TasksTab(vm, callActive) { tab = Tab.PROTECTION }
                }
            }
            BottomNav(tab) { tab = it }
        }

        // Consent takeover sits above everything while the handshake is in progress.
        if (callActive && consent == ConsentState.REQUESTING) {
            ConsentScreen(
                onConsent = { AudimusBridge.pipeline?.grantConsent() },
                onDecline = { AudimusBridge.pipeline?.endCall() },
            )
        }
    }
}

/* ----------------------------- Active call ----------------------------- */

@Composable
private fun ActiveCallView() {
    val aud = AudimusTheme.colors
    val serviceConnected by ProtectionState.serviceConnected.collectAsStateWithLifecycle()
    val scraping by ProtectionState.scraping.collectAsStateWithLifecycle()
    val usingGemma by ProtectionState.usingGemma.collectAsStateWithLifecycle()
    val transcript by ProtectionState.transcript.collectAsStateWithLifecycle()
    val risk by ProtectionState.riskLevel.collectAsStateWithLifecycle()
    val reason by ProtectionState.riskReason.collectAsStateWithLifecycle()
    val conf by ProtectionState.riskConfidence.collectAsStateWithLifecycle()
    val consent by ProtectionState.consentState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // TOP STATUS BAR (pinned)
        Column(
            Modifier.fillMaxWidth().background(aud.surface).padding(16.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Protected", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = aud.text)
                Spacer(Modifier.weight(1f))
                StatusDot(if (serviceConnected) aud.safe else aud.med, "Service")
                Spacer(Modifier.width(12.dp))
                StatusDot(if (scraping) aud.safe else aud.text3, "Live Caption")
            }
            Chip(if (usingGemma) "Gemma 4 E2B · on-device" else "Keyword mode · on-device", aud.surface2, aud.text2, aud.primary)
        }

        // RISK BANNER
        Box(Modifier.padding(14.dp, 12.dp, 14.dp, 0.dp)) {
            if (consent == ConsentState.DECLINED || consent == ConsentState.NO_RESPONSE) {
                SoftStopBanner()
            } else {
                RiskBanner(risk, reason, conf)
            }
        }

        // TRANSCRIPT (scroll middle)
        Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 4.dp)) {
            SectionLabel("Live transcript")
            Spacer(Modifier.height(10.dp))
            if (transcript.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("💬", fontSize = 30.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Waiting for captions… turn on Live Caption during the call.",
                        color = aud.text2, fontSize = 13.sp,
                        style = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    )
                }
            } else {
                val shown = transcript.takeLast(60)
                val listState = rememberLazyListState()
                // Auto-scroll to the newest line whenever one arrives.
                LaunchedEffect(shown.size) {
                    if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1)
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown) { TranscriptLine(it) }
                }
            }
        }

        SimulatorBar()
    }
}

@Composable
private fun RiskBanner(risk: RiskLevel, reason: String, conf: Float) {
    val aud = AudimusTheme.colors
    val high = risk == RiskLevel.HIGH
    val bg: Color; val fg: Color; val icon: String; val title: String
    when (risk) {
        RiskLevel.HIGH -> { bg = aud.high; fg = Color.White; icon = "⚠"; title = "LIKELY SCAM" }
        RiskLevel.MEDIUM -> { bg = aud.med; fg = Color(0xFF201A0B); icon = "⚠"; title = "CAUTION" }
        RiskLevel.LOW -> { bg = aud.surface; fg = aud.text; icon = "✓"; title = "PROTECTED" }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, if (high) bg else aud.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = if (high) 30.sp else 22.sp, color = if (risk == RiskLevel.LOW) aud.safe else fg)
            Spacer(Modifier.width(11.dp))
            Column {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = if (high) 24.sp else 18.sp, color = fg)
                if (conf > 0f && risk != RiskLevel.LOW) {
                    Text("Confidence ${(conf * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.85f))
                }
            }
        }
        if (reason.isNotBlank()) {
            Text(reason, fontSize = 14.sp, color = fg.copy(alpha = 0.95f))
        } else if (risk == RiskLevel.LOW) {
            Text("No scam signals detected.", fontSize = 14.sp, color = aud.text2)
        }
        if (high) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x38000000)).padding(10.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🚫", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Do not share codes, PINs, or payment details.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun SoftStopBanner() {
    val aud = AudimusTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(aud.surface)
            .border(1.dp, aud.outline, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Protection paused", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = aud.med)
        Text("The caller did not consent, so Audimus is not analyzing this call.", fontSize = 13.5.sp, color = aud.text2)
    }
}

@Composable
private fun SimulatorBar() {
    val aud = AudimusTheme.colors
    var open by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(aud.bg).border(1.dp, aud.outline, RoundedCornerShape(0.dp)).padding(14.dp, 8.dp, 14.dp, 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Demo simulator")
            Spacer(Modifier.weight(1f))
            Text(if (open) "▾" else "▸", color = aud.text3, fontSize = 18.sp)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            SimpleField(input, { input = it }, "Transcript line…") {
                submit(input); input = ""
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Send", aud.primary, aud.onPrimary) { submit(input); input = "" }
                PillButton("Clear", aud.surface2, aud.text2) { AudimusBridge.pipeline?.clear() }
                Spacer(Modifier.weight(1f))
                PillButton("End call", aud.highC, aud.high) { AudimusBridge.pipeline?.endCall() }
            }
        }
    }
}

private fun submit(text: String) {
    val t = text.trim()
    if (t.isNotEmpty()) AudimusBridge.pipeline?.appendUtterance(t)
}

/* ----------------------------- Consent ----------------------------- */

@Composable
private fun ConsentScreen(onConsent: () -> Unit, onDecline: () -> Unit) {
    val aud = AudimusTheme.colors
    Column(
        Modifier.fillMaxSize().background(aud.bg).windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp, 26.dp, 24.dp, 30.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("INCOMING CALL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = aud.text3, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(6.dp))
            Text("Unknown number", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = aud.text)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(aud.primary), contentAlignment = Alignment.Center) {
                Text("👂", fontSize = 38.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Requesting consent", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = aud.text)
                Spacer(Modifier.height(6.dp))
                Text("Waiting for a spoken yes / no", fontSize = 13.sp, color = aud.text2)
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(aud.surface)
                    .border(1.dp, aud.outline, RoundedCornerShape(20.dp)).padding(18.dp),
            ) {
                Text("NOW PLAYING TO CALLER", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = aud.primary, letterSpacing = 0.3.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "\"This call may be analyzed by Audimus for scam protection. Do you consent?\"",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = aud.text,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Decline", aud.surface, aud.text2, onClick = onDecline)
                Box(Modifier.weight(1f)) {
                    PillButton("Caller consents", aud.safe, Color.White, fill = true, onClick = onConsent)
                }
            }
        }
    }
}

/* ----------------------------- Dashboard (idle) ----------------------------- */

@Composable
private fun DashboardView(vm: DashboardViewModel) {
    val aud = AudimusTheme.colors
    val serviceConnected by ProtectionState.serviceConnected.collectAsStateWithLifecycle()
    val usingGemma by ProtectionState.usingGemma.collectAsStateWithLifecycle()
    val recent by vm.recentCalls.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp, 12.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Audimus", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = aud.primary)

        // status hero
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(if (serviceConnected) aud.primary else aud.surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (serviceConnected) Color(0x24000000) else aud.medC),
                contentAlignment = Alignment.Center,
            ) { Text(if (serviceConnected) "🛡" else "🌙", fontSize = 26.sp) }
            Text(
                if (serviceConnected) "Protected" else "Setup needed",
                fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                color = if (serviceConnected) aud.onPrimary else aud.text,
            )
            Text(
                if (serviceConnected) "Calls are screened live. Nothing leaves your phone."
                else "Enable the accessibility service to start screening calls.",
                fontSize = 13.5.sp,
                color = if (serviceConnected) aud.onPrimary.copy(alpha = 0.82f) else aud.text2,
            )
            Chip(if (usingGemma) "Gemma 4 E2B · on-device" else "Keyword mode",
                if (serviceConnected) Color(0x1FFFFFFF) else aud.surface2,
                if (serviceConnected) aud.onPrimary else aud.text2,
                if (serviceConnected) aud.onPrimary else aud.primary)
        }

        // simulate an incoming call
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(aud.surface)
                .border(1.dp, aud.outline, RoundedCornerShape(18.dp))
                .clickable { AudimusBridge.pipeline?.startSimulatedCall() }.padding(15.dp, 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(aud.primaryC), contentAlignment = Alignment.Center) {
                Text("▶", fontSize = 16.sp, color = aud.onPrimaryC)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Simulate an incoming call", fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = aud.text)
                Text("Consent → live protection", fontSize = 12.sp, color = aud.text2)
            }
            Text("›", fontSize = 22.sp, color = aud.text3)
        }
        if (AudimusBridge.pipeline == null) {
            Text("Enable the accessibility service first so the analyzer is running.", fontSize = 12.sp, color = aud.med)
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("Recent calls")
        if (recent.isEmpty()) {
            Text("No calls screened yet. Protected calls show up here with their verdict.", fontSize = 13.sp, color = aud.text2)
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(aud.surface).border(1.dp, aud.outline, RoundedCornerShape(18.dp)),
            ) {
                recent.forEach { RecentCallRow(it) }
            }
        }
    }
}

@Composable
private fun RecentCallRow(rec: CallRecord) {
    val aud = AudimusTheme.colors
    val level = runCatching { RiskLevel.valueOf(rec.riskLevel) }.getOrDefault(RiskLevel.LOW)
    val (badge, badgeBg, badgeFg, iconBg, iconFg, icon) = when (level) {
        RiskLevel.HIGH -> Sextet("SCAM", aud.high, Color.White, aud.highC, aud.high, "⚠")
        RiskLevel.MEDIUM -> Sextet("CAUTION", aud.medC, aud.med, aud.medC, aud.med, "⚠")
        RiskLevel.LOW -> Sextet("SAFE", aud.safeC, aud.safe, aud.safeC, aud.safe, "✓")
    }
    Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 16.sp, color = iconFg)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(rec.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = aud.text, maxLines = 1)
            Text("${clockFmt.format(Date(rec.timestampMs))} · ${fmtDuration(rec.durationSec)}", fontSize = 12.sp, color = aud.text2)
        }
        Box(Modifier.clip(RoundedCornerShape(100.dp)).background(badgeBg).padding(8.dp, 4.dp)) {
            Text(badge, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = badgeFg)
        }
    }
}

private data class Sextet(
    val badge: String, val badgeBg: Color, val badgeFg: Color,
    val iconBg: Color, val iconFg: Color, val icon: String,
)

private fun fmtDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/* ----------------------------- Calendar / Tasks ----------------------------- */

@Composable
private fun CalendarTab(vm: DashboardViewModel, callActive: Boolean, onReturn: () -> Unit) {
    val aud = AudimusTheme.colors
    val events by vm.events.collectAsStateWithLifecycle()
    TabPage("Calendar", "Captured from your calls", callActive, onReturn, empty = events.isEmpty(),
        emptyIcon = "🗓", emptyTitle = "No events yet",
        emptyText = "When someone mentions a date or meeting on a call, Audimus adds it here automatically.") {
        items(events) { EventRow(it) }
    }
}

@Composable
private fun TasksTab(vm: DashboardViewModel, callActive: Boolean, onReturn: () -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    TabPage("Tasks", "Captured from your calls", callActive, onReturn, empty = tasks.isEmpty(),
        emptyIcon = "☑", emptyTitle = "No tasks captured",
        emptyText = "Action items mentioned on a call land here as to-dos.") {
        items(tasks) { TaskRow(it) }
    }
}

@Composable
private fun TabPage(
    title: String,
    subtitle: String,
    callActive: Boolean,
    onReturn: () -> Unit,
    empty: Boolean,
    emptyIcon: String,
    emptyTitle: String,
    emptyText: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val aud = AudimusTheme.colors
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 8.dp)) {
        if (callActive) {
            Row(
                Modifier.clip(RoundedCornerShape(100.dp)).background(aud.primaryC).clickable(onClick = onReturn).padding(12.dp, 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(aud.primary))
                Spacer(Modifier.width(8.dp))
                Text("Protecting call · tap to return", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = aud.onPrimaryC)
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = aud.text)
        Text(subtitle, fontSize = 13.5.sp, color = aud.text2)
        Spacer(Modifier.height(16.dp))
        if (empty) {
            Column(
                Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(aud.surface), contentAlignment = Alignment.Center) {
                    Text(emptyIcon, fontSize = 34.sp)
                }
                Spacer(Modifier.height(20.dp))
                Text(emptyTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = aud.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    emptyText, fontSize = 13.5.sp, color = aud.text2,
                    style = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun EventRow(e: CreatedCalendarEvent) {
    val aud = AudimusTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(aud.surface).border(1.dp, aud.outline, RoundedCornerShape(18.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val d = Date(e.startMillis)
            Text(SimpleDateFormat("d", Locale.US).format(d), fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = aud.primary)
            Text(SimpleDateFormat("MMM", Locale.US).format(d).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = aud.text3)
        }
        Box(Modifier.width(1.dp).height(48.dp).background(aud.outline))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = aud.text)
            Text(dateFmt.format(Date(e.startMillis)) + (e.personName?.let { " · $it" } ?: ""), fontSize = 12.5.sp, color = aud.text2)
            Chip("From call · ${e.sourceCall}", aud.surface2, aud.text3, aud.primary)
            if (e.calendarEventId < 0) Text("(not written to device calendar)", fontSize = 11.sp, color = aud.med)
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem) {
    val aud = AudimusTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(aud.surface).border(1.dp, aud.outline, RoundedCornerShape(18.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("○", fontSize = 20.sp, color = aud.text3)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = aud.text, textDecoration = TextDecoration.None)
            Text("${t.assignee ?: "You"} · ${dateFmt.format(Date(t.timestampMs))}", fontSize = 12.sp, color = aud.text3)
        }
    }
}

/* ----------------------------- Shared ----------------------------- */

@Composable
private fun TranscriptLine(entry: TranscriptEntry) {
    val aud = AudimusTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(timeFmt.format(Date(entry.timestampMs)), fontSize = 11.sp, color = aud.text3, modifier = Modifier.width(52.dp))
        Text(entry.text, fontSize = 14.sp, color = aud.text2)
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    val aud = AudimusTheme.colors
    Row(
        Modifier.fillMaxWidth().background(aud.surface).padding(14.dp, 8.dp, 14.dp, 12.dp),
    ) {
        Tab.entries.forEach { t ->
            val active = t == current
            Column(
                Modifier.weight(1f).clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.height(30.dp).width(60.dp).clip(RoundedCornerShape(100.dp))
                        .background(if (active) aud.primaryC else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Text(t.glyph, fontSize = 18.sp, color = if (active) aud.onPrimaryC else aud.text2) }
                Spacer(Modifier.height(4.dp))
                Text(t.label, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) aud.onPrimaryC else aud.text2)
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, label: String) {
    val aud = AudimusTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = aud.text2)
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, dot: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(100.dp)).background(bg).padding(10.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = AudimusTheme.colors.text3)
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, fill: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(100.dp)).background(bg).clickable(onClick = onClick).padding(16.dp, 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SimpleField(value: String, onChange: (String) -> Unit, placeholder: String, onSubmit: () -> Unit) {
    val aud = AudimusTheme.colors
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = aud.text3) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSubmit() }),
    )
}
