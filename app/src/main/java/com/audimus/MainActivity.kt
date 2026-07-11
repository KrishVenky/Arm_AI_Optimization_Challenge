package com.audimus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audimus.ui.HomeScreen
import com.audimus.ui.OnboardingScreen
import com.audimus.ui.isAudimusAccessibilityEnabled
import com.audimus.ui.theme.AudimusTheme

class MainActivity : ComponentActivity() {

    private val runtimePermissions = arrayOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private var onPermissionResult: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermissionResult?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudimusTheme {
                var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }
                var accessibilityOn by remember { mutableStateOf(isAudimusAccessibilityEnabled(this)) }
                var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(this)) }

                // Re-check every time we come back from a Settings screen.
                LifecycleResumeEffect(Unit) {
                    permissionsGranted = hasAllPermissions()
                    accessibilityOn = isAudimusAccessibilityEnabled(this@MainActivity)
                    overlayOn = Settings.canDrawOverlays(this@MainActivity)
                    onPauseOrDispose { }
                }

                if (permissionsGranted && accessibilityOn) {
                    HomeScreen(modifier = Modifier.fillMaxSize())
                } else {
                    OnboardingScreen(
                        permissionsGranted = permissionsGranted,
                        accessibilityOn = accessibilityOn,
                        overlayOn = overlayOn,
                        onRequestPermissions = {
                            onPermissionResult = { permissionsGranted = hasAllPermissions() }
                            permissionLauncher.launch(runtimePermissions)
                        },
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenOverlay = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean = runtimePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

/** True if the given runtime permission is granted. */
fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
