package com.audimus.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** One rolling capture window of 16 kHz mono PCM16 audio. */
class AudioWindow(
    val samples: ShortArray,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/**
 * Stage 2: continuous microphone capture into rolling, overlapping windows.
 *
 * 4-second windows with 1 second of overlap (a new window every 3 seconds), so a
 * word spoken across a boundary is always fully contained in one of the two
 * adjacent windows.
 *
 * Uses VOICE_RECOGNITION as the source: unlike VOICE_COMMUNICATION it applies no
 * echo cancellation, which matters because the far-end caller's voice arrives via
 * the loudspeaker and echo cancellation would treat it as noise to remove.
 */
class AudioCaptureEngine(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SECONDS = 4
        const val OVERLAP_SECONDS = 1
        const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS
        const val HOP_SAMPLES = SAMPLE_RATE * (WINDOW_SECONDS - OVERLAP_SECONDS)
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100 ms reads
    }

    private val _windows = MutableSharedFlow<AudioWindow>(extraBufferCapacity = 8)
    val windows: SharedFlow<AudioWindow> = _windows.asSharedFlow()

    /** RMS level of the most recent 100 ms chunk, normalized to 0..1. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _windowsEmitted = MutableStateFlow(0)
    val windowsEmitted: StateFlow<Int> = _windowsEmitted.asStateFlow()

    private var captureJob: Job? = null

    @SuppressLint("MissingPermission") // checked explicitly below
    fun start() {
        if (captureJob != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; capture not started")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, WINDOW_SAMPLES * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }

        captureJob = scope.launch(Dispatchers.IO) {
            val ring = ShortArray(WINDOW_SAMPLES)
            var ringFill = 0            // valid samples in ring (grows to WINDOW_SAMPLES)
            var samplesSinceEmit = 0
            val chunk = ShortArray(CHUNK_SAMPLES)
            val captureStartMs = System.currentTimeMillis()
            var totalSamples = 0L

            record.startRecording()
            _isCapturing.value = true
            try {
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) {
                        Log.w(TAG, "AudioRecord.read returned $read")
                        continue
                    }
                    totalSamples += read
                    _level.value = rms(chunk, read)

                    // Slide the ring left if the new chunk would overflow it.
                    if (ringFill + read > ring.size) {
                        val shift = ringFill + read - ring.size
                        System.arraycopy(ring, shift, ring, 0, ringFill - shift)
                        ringFill -= shift
                    }
                    System.arraycopy(chunk, 0, ring, ringFill, read)
                    ringFill += read
                    samplesSinceEmit += read

                    if (ringFill == WINDOW_SAMPLES && samplesSinceEmit >= HOP_SAMPLES) {
                        samplesSinceEmit = 0
                        val endMs = captureStartMs + (totalSamples * 1000 / SAMPLE_RATE)
                        _windows.tryEmit(
                            AudioWindow(
                                samples = ring.copyOf(),
                                startTimeMs = endMs - WINDOW_SECONDS * 1000L,
                                endTimeMs = endMs,
                            ),
                        )
                        _windowsEmitted.value += 1
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                _isCapturing.value = false
                _level.value = 0f
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
    }

    private fun rms(buf: ShortArray, len: Int): Float {
        var sum = 0.0
        for (i in 0 until len) {
            val s = buf[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / len).toFloat()
    }
}
