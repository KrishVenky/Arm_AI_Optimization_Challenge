package com.audimus.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One finalized utterance in the running transcript. */
data class TranscriptEntry(
    val timestampMs: Long,
    val text: String,
)

/**
 * Stage 3: continuous on-device speech-to-text.
 *
 * Uses [SpeechRecognizer.createOnDeviceSpeechRecognizer] (API 31+), which runs
 * entirely on the device, with [RecognizerIntent.EXTRA_PREFER_OFFLINE] set as a
 * belt-and-braces guarantee. Android's recognizer stops after each utterance, so
 * we restart it as soon as results (or a recoverable error) arrive to get a
 * running transcript for the whole call.
 */
class TranscriptionEngine(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "TranscriptionEngine"
        private const val RESTART_DELAY_MS = 150L
        private const val ERROR_BACKOFF_MS = 1_000L
    }

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    /** Words for the utterance currently in flight (partial hypothesis). */
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var shouldListen = false
    private var restartJob: Job? = null

    /** Callback invoked with each finalized utterance (used later by consent + analysis). */
    var onFinalUtterance: ((TranscriptEntry) -> Unit)? = null

    private val recognitionIntent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Let the caller pause briefly without ending the session.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _statusMessage.value = "Listening"
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _isListening.value = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) _partialText.value = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            _partialText.value = ""
            if (text.isNotEmpty()) {
                val entry = TranscriptEntry(System.currentTimeMillis(), text)
                _transcript.value = _transcript.value + entry
                onFinalUtterance?.invoke(entry)
            }
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            _isListening.value = false
            _partialText.value = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> scheduleRestart(RESTART_DELAY_MS) // silence — just listen again

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                -> scheduleRestart(ERROR_BACKOFF_MS)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _statusMessage.value = "Microphone permission missing"
                    shouldListen = false
                }

                else -> {
                    Log.w(TAG, "Recognizer error $error, backing off")
                    _statusMessage.value = "Recognizer error $error"
                    scheduleRestart(ERROR_BACKOFF_MS)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Must be called from the main thread (SpeechRecognizer requirement). */
    fun start() {
        if (shouldListen) return
        shouldListen = true

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            _statusMessage.value = "On-device recognition unavailable"
            Log.e(TAG, "On-device speech recognition not available on this device")
            shouldListen = false
            return
        }

        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }
        startListening()
    }

    fun stop() {
        shouldListen = false
        restartJob?.cancel()
        restartJob = null
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
        _partialText.value = ""
        _statusMessage.value = "Stopped"
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    private fun startListening() {
        if (!shouldListen) return
        try {
            recognizer?.startListening(recognitionIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            scheduleRestart(ERROR_BACKOFF_MS)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!shouldListen) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            startListening()
        }
    }
}
