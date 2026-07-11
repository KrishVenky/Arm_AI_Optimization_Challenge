package com.audimus.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.audimus.service.AudimusAccessibilityService

/** Whether the Audimus accessibility service is currently enabled in system settings. */
fun isAudimusAccessibilityEnabled(context: Context): Boolean {
    val component = ComponentName(context, AudimusAccessibilityService::class.java)
    // Settings stores enabled services as flattened ComponentNames; OEM builds vary between the
    // full form (pkg/pkg.Class) and the short form (pkg/.Class), so accept either.
    val expected = setOf(component.flattenToString(), component.flattenToShortString())
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    for (component in splitter) {
        if (expected.any { it.equals(component, ignoreCase = true) }) return true
    }
    return false
}
