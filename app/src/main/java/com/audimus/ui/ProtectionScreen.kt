package com.audimus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audimus.AudimusViewModel
import com.audimus.call.CallState
import com.audimus.stt.TranscriptEntry
import com.audimus.ui.theme.RiskMedium
import com.audimus.ui.theme.SafeGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun ProtectionScreen(viewModel: AudimusViewModel, modifier: Modifier = Modifier) {
    val callState by viewModel.callMonitor.callState.collectAsStateWithLifecycle()
    val speakerOn by viewModel.callMonitor.speakerphoneOn.collectAsStateWithLifecycle()
    val isCapturing by viewModel.audioCapture.isCapturing.collectAsStateWithLifecycle()
    val level by viewModel.audioCapture.level.collectAsStateWithLifecycle()
    val windowCount by viewModel.audioCapture.windowsEmitted.collectAsStateWithLifecycle()
    val transcript by viewModel.transcription.transcript.collectAsStateWithLifecycle()
    val partialText by viewModel.transcription.partialText.collectAsStateWithLifecycle()
    val isListening by viewModel.transcription.isListening.collectAsStateWithLifecycle()
    val sttStatus by viewModel.transcription.statusMessage.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
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

        CallStatusCard(callState, speakerOn)

        if (callState == CallState.ACTIVE && !speakerOn) {
            SpeakerphonePrompt()
        }

        CaptureCard(
            isCapturing = isCapturing,
            level = level,
            windowCount = windowCount,
            onStart = viewModel::startCapture,
            onStop = viewModel::stopCapture,
        )

        TranscriptCard(
            transcript = transcript,
            partialText = partialText,
            isListening = isListening,
            status = sttStatus,
            onStart = viewModel::startTranscription,
            onStop = viewModel::stopTranscription,
            onClear = viewModel.transcription::clearTranscript,
        )
    }
}

@Composable
private fun TranscriptCard(
    transcript: List<TranscriptEntry>,
    partialText: String,
    isListening: Boolean,
    status: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live transcript", style = MaterialTheme.typography.labelLarge)
                if (isListening) {
                    Box(Modifier.size(8.dp).background(SafeGreen, CircleShape))
                }
                Spacer(Modifier.weight(1f))
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (transcript.isEmpty() && partialText.isBlank()) {
                Text(
                    "Nothing transcribed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    transcript.forEach { entry ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                timeFormat.format(Date(entry.timestampMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (partialText.isNotBlank()) {
                        Text(
                            partialText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !isListening) { Text("Start STT") }
                OutlinedButton(onClick = onStop) { Text("Stop") }
                OutlinedButton(onClick = onClear, enabled = transcript.isNotEmpty()) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun CallStatusCard(callState: CallState, speakerOn: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Call status", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            when (callState) {
                                CallState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                CallState.RINGING -> RiskMedium
                                CallState.ACTIVE -> SafeGreen
                            },
                            CircleShape,
                        ),
                )
                Text(
                    when (callState) {
                        CallState.IDLE -> "No active call"
                        CallState.RINGING -> "Incoming call ringing"
                        CallState.ACTIVE -> "Call in progress"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (callState == CallState.ACTIVE) {
                Text(
                    if (speakerOn) "Speakerphone is on" else "Speakerphone is off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (speakerOn) SafeGreen else RiskMedium,
                )
            }
        }
    }
}

@Composable
private fun SpeakerphonePrompt() {
    Card(colors = CardDefaults.cardColors(containerColor = RiskMedium.copy(alpha = 0.15f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Enable speakerphone", style = MaterialTheme.typography.titleMedium, color = RiskMedium)
            Text(
                "Audimus listens through the loudspeaker. Tap the speaker button in your call screen so both sides of the conversation can be heard.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CaptureCard(
    isCapturing: Boolean,
    level: Float,
    windowCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Audio capture", style = MaterialTheme.typography.labelLarge)

            LevelMeter(level)

            Text(
                if (isCapturing) "Capturing — $windowCount windows emitted (4 s / 1 s overlap)"
                else "Capture stopped",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !isCapturing) { Text("Start capture") }
                OutlinedButton(onClick = onStop, enabled = isCapturing) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    // Perceptual-ish scaling so quiet speech is still visible.
    val fraction = (level * 6f).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(
                    if (fraction > 0.85f) RiskMedium else SafeGreen,
                    RoundedCornerShape(5.dp),
                ),
        )
    }
}
