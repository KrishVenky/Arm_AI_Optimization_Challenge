package com.audimus.consent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.audimus.core.ProtectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Per-call consent handshake. When a call starts, Audimus speaks a short disclosure to the caller
 * through the phone speaker (on-device [TextToSpeech]) and then classifies the caller's spoken
 * reply — read from the running transcript — as yes / no. Until consent is granted, the pipeline
 * does not run scam analysis.
 *
 * All outcomes are mirrored into [ProtectionState.consentState]. Nothing here blocks or throws.
 */
class ConsentController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak = false
    private var timeoutJob: Job? = null

    /** True while our own disclosure is playing — so Live Caption's echo of it isn't read back. */
    @Volatile private var speaking = false

    /** Ignore transcript until this time (guards caption lag after the disclosure finishes). */
    @Volatile private var ignoreUntilMs = 0L

    /** Transcript length already inspected, so we only classify newly-spoken words. */
    private var scannedChars = 0

    val state: ConsentState get() = ProtectionState.consentState.value

    /** Begin the handshake: play the disclosure and await a spoken yes/no (12 s timeout). */
    fun start() {
        if (state == ConsentState.REQUESTING) return
        scannedChars = 0
        ProtectionState.setConsentState(ConsentState.REQUESTING)
        ensureTts()
        speakDisclosure()
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CONSENT_TIMEOUT_MS)
            if (state == ConsentState.REQUESTING) {
                Log.i(TAG, "No consent response within timeout — soft-stop")
                ProtectionState.setConsentState(ConsentState.NO_RESPONSE)
            }
        }
    }

    /** Feed the whole running transcript; classifies only the part not seen yet. */
    fun onTranscript(fullTranscript: String) {
        if (state != ConsentState.REQUESTING) return
        // While our disclosure is playing (or just after), Live Caption echoes our own "…yes or no"
        // prompt — skip it, and keep the scan cursor at the end so that echo is never classified.
        if (speaking || System.currentTimeMillis() < ignoreUntilMs) {
            scannedChars = fullTranscript.length
            return
        }
        val fresh = if (scannedChars <= fullTranscript.length) {
            fullTranscript.substring(scannedChars)
        } else {
            fullTranscript
        }
        scannedChars = fullTranscript.length
        val t = fresh.lowercase()
        when {
            YES.any { it in t } -> resolve(ConsentState.CONSENTED, "spoken yes")
            NO.any { it in t } -> resolve(ConsentState.DECLINED, "spoken no")
        }
    }

    /** Demo / manual override from the consent UI. */
    fun consentManually() = resolve(ConsentState.CONSENTED, "manual")
    fun declineManually() = resolve(ConsentState.DECLINED, "manual")

    private fun resolve(result: ConsentState, why: String) {
        if (state != ConsentState.REQUESTING) return
        timeoutJob?.cancel()
        Log.i(TAG, "Consent resolved: $result ($why)")
        ProtectionState.setConsentState(result)
    }

    fun reset() {
        timeoutJob?.cancel()
        scannedChars = 0
        runCatching { tts?.stop() }
        ProtectionState.setConsentState(ConsentState.IDLE)
    }

    fun shutdown() {
        timeoutJob?.cancel()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching { tts?.language = Locale.US }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false; ignoreUntilMs = System.currentTimeMillis() + POST_SPEAK_GUARD_MS }
                    @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) { speaking = false; ignoreUntilMs = System.currentTimeMillis() + POST_SPEAK_GUARD_MS }
                })
                if (pendingSpeak) { pendingSpeak = false; speakDisclosure() }
            } else {
                Log.w(TAG, "TextToSpeech init failed ($status); consent will rely on manual/timeout")
            }
        }
    }

    private fun speakDisclosure() {
        val engine = tts
        if (engine == null || !ttsReady) { pendingSpeak = true; return }
        speaking = true
        ignoreUntilMs = System.currentTimeMillis() + POST_SPEAK_GUARD_MS
        runCatching {
            engine.speak(DISCLOSURE, TextToSpeech.QUEUE_FLUSH, null, "audimus-consent")
        }
    }

    private companion object {
        const val TAG = "ConsentController"
        const val CONSENT_TIMEOUT_MS = 12_000L
        const val POST_SPEAK_GUARD_MS = 1_800L
        const val DISCLOSURE =
            "This call may be analyzed by Audimus for scam protection. Do you consent? " +
                "Please say yes or no."
        val YES = listOf("yes", "yeah", "yep", "sure", "okay", "ok", "go ahead", "i consent", "fine")
        val NO = listOf("no", "nope", "don't", "do not", "decline", "stop", "i refuse", "not ok")
    }
}
