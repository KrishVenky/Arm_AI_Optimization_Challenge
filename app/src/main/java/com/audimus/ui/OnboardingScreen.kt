package com.audimus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audimus.ui.theme.SafeGreen

@Composable
fun OnboardingScreen(
    permissionsGranted: Boolean,
    accessibilityOn: Boolean,
    overlayOn: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Audimus",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "On-device scam-call protection. Audimus reads the live transcript already shown on your screen by a captioning app, then reasons over the text entirely on your phone. It never records audio, and nothing is ever sent to a server.",
            style = MaterialTheme.typography.bodyLarge,
        )

        SetupStep(
            number = 1,
            title = "Grant permissions",
            detail = "Notifications and calendar (to save meetings mentioned on a call).",
            done = permissionsGranted,
            actionLabel = "Grant",
            onAction = onRequestPermissions,
        )
        SetupStep(
            number = 2,
            title = "Enable the Audimus accessibility service",
            detail = "This lets Audimus read the on-screen transcript text. Find Audimus under Settings ▸ Accessibility and turn it on.",
            done = accessibilityOn,
            actionLabel = "Open settings",
            onAction = onOpenAccessibility,
        )
        SetupStep(
            number = 3,
            title = "Allow the warning overlay (optional)",
            detail = "Lets Audimus show a full-screen scam warning over other apps.",
            done = overlayOn,
            actionLabel = "Allow",
            onAction = onOpenOverlay,
        )

        Spacer(Modifier.size(8.dp))
        Text(
            "Audimus reads text, not audio — the same capability a screen reader uses. Turn on your phone's live captions (or a transcription app) during a call and Audimus analyzes the transcript. You can also try it right now with the built-in simulator on the Protection tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    detail: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) SafeGreen.copy(alpha = 0.14f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${if (done) "✓" else number}  $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) SafeGreen else MaterialTheme.colorScheme.onSurface,
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            if (!done) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
