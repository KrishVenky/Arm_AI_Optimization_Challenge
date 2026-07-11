package com.audimus.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CallState { IDLE, RINGING, ACTIVE }

/**
 * Stage 1: watches the device call state and speakerphone routing.
 *
 * Audimus can only hear the far side of a call through the loudspeaker, so when a
 * call goes active without speakerphone we surface a prompt asking the user to
 * enable it. We deliberately do not force audio routing ourselves: the call audio
 * session belongs to the dialer, not to us.
 */
class CallMonitor(private val context: Context, private val scope: CoroutineScope) {

    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _speakerphoneOn = MutableStateFlow(false)
    val speakerphoneOn: StateFlow<Boolean> = _speakerphoneOn.asStateFlow()

    private var telephonyCallback: TelephonyCallback? = null
    private var speakerPollJob: Job? = null

    fun start() {
        if (telephonyCallback != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                _callState.value = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                    TelephonyManager.CALL_STATE_OFFHOOK -> CallState.ACTIVE
                    else -> CallState.IDLE
                }
            }
        }
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
        telephonyCallback = callback

        // There is no reliable callback for another app's audio routing, so poll
        // the speakerphone state once a second.
        speakerPollJob = scope.launch {
            while (isActive) {
                _speakerphoneOn.value = isSpeakerphoneActive()
                delay(1_000)
            }
        }
    }

    fun stop() {
        telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        telephonyCallback = null
        speakerPollJob?.cancel()
        speakerPollJob = null
    }

    @Suppress("DEPRECATION")
    private fun isSpeakerphoneActive(): Boolean {
        val commDevice = audioManager.communicationDevice
        return commDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            audioManager.isSpeakerphoneOn
    }
}
