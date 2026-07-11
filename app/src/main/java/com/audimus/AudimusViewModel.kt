package com.audimus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audimus.audio.AudioCaptureEngine
import com.audimus.call.CallMonitor
import com.audimus.stt.TranscriptionEngine

class AudimusViewModel(application: Application) : AndroidViewModel(application) {

    val callMonitor = CallMonitor(application, viewModelScope)
    val audioCapture = AudioCaptureEngine(application, viewModelScope)
    val transcription = TranscriptionEngine(application, viewModelScope)

    /** Called once runtime permissions are granted. */
    fun onPermissionsGranted() {
        callMonitor.start()
    }

    fun startCapture() = audioCapture.start()

    fun stopCapture() = audioCapture.stop()

    fun startTranscription() = transcription.start()

    fun stopTranscription() = transcription.stop()

    override fun onCleared() {
        transcription.stop()
        audioCapture.stop()
        callMonitor.stop()
    }
}
