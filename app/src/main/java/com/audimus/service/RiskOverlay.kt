package com.audimus.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.audimus.core.AudimusBridge
import com.audimus.core.ProtectionState
import com.audimus.model.RiskLevel
import com.audimus.ui.theme.AudimusTheme
import com.audimus.ui.theme.RiskHigh

/**
 * A full-screen warning overlay drawn over the in-call screen when risk is HIGH (stage 6).
 *
 * Uses a `TYPE_APPLICATION_OVERLAY` window (requires SYSTEM_ALERT_WINDOW, requested in onboarding).
 * Hosts Compose by giving the [ComposeView] the ViewTree owners it needs outside an Activity.
 */
class RiskOverlay(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null
    private var onDismiss: () -> Unit = {}

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun setOnDismiss(block: () -> Unit) { onDismiss = block }

    @SuppressLint("InflateParams")
    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) return

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@RiskOverlay)
            setViewTreeViewModelStoreOwner(this@RiskOverlay)
            setViewTreeSavedStateRegistryOwner(this@RiskOverlay)
            setContent { OverlayContent(onDismiss = { onDismiss() }) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        runCatching { windowManager.addView(compose, params) }
        view = compose
    }

    fun hide() {
        val v = view ?: return
        runCatching { windowManager.removeView(v) }
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        hide()
        store.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

@androidx.compose.runtime.Composable
private fun OverlayContent(onDismiss: () -> Unit) {
    val risk by ProtectionState.riskLevel.collectAsStateWithLifecycle()
    val reason by ProtectionState.riskReason.collectAsStateWithLifecycle()
    val confidence by ProtectionState.riskConfidence.collectAsStateWithLifecycle()

    // If risk de-escalated below HIGH, don't paint the alarm.
    if (risk != RiskLevel.HIGH) return

    AudimusTheme(darkTheme = true) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC1A1A18)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = RiskHigh),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("HIGH RISK", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                    Text("⚠  LIKELY SCAM", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        reason.ifBlank { "This call shows strong signs of a scam." },
                        style = MaterialTheme.typography.bodyLarge, color = Color.White,
                    )
                    if (confidence > 0f) {
                        Text("Confidence ${(confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color(0x38000000), MaterialTheme.shapes.medium)
                            .padding(14.dp),
                    ) {
                        Text("🚫  Do not share codes, PINs, or payment details.",
                            style = MaterialTheme.typography.bodyMedium, color = Color.White,
                            fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { runCatching { AudimusBridge.pipeline?.endCall() }; onDismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("End call") }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White, contentColor = RiskHigh),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Dismiss") }
                }
            }
        }
    }
}
