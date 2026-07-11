package com.audimus.core

import com.audimus.consent.ConsentState
import com.audimus.model.AnalysisResult
import com.audimus.model.RiskLevel
import com.audimus.stt.TranscriptEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide, process-singleton snapshot of live protection state for the transcript-scraping MVP.
 *
 * Written by [com.audimus.service.AudimusAccessibilityService] / [AnalysisPipeline]; observed by the
 * dashboard and the in-call risk overlay. Audimus reads on-screen transcript text (via the
 * accessibility node tree) — it never touches audio — so there are no capture/mic fields here.
 */
object ProtectionState {

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    /** The app whose on-screen transcript Audimus is currently reading (e.g. Live Transcribe). */
    private val _sourceApp = MutableStateFlow<String?>(null)
    val sourceApp: StateFlow<String?> = _sourceApp.asStateFlow()

    /** Whether a transcript is actively being scraped right now. */
    private val _scraping = MutableStateFlow(false)
    val scraping: StateFlow<Boolean> = _scraping.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _riskLevel = MutableStateFlow(RiskLevel.LOW)
    val riskLevel: StateFlow<RiskLevel> = _riskLevel.asStateFlow()

    private val _riskReason = MutableStateFlow("")
    val riskReason: StateFlow<String> = _riskReason.asStateFlow()

    private val _riskConfidence = MutableStateFlow(0f)
    val riskConfidence: StateFlow<Float> = _riskConfidence.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<AnalysisResult?>(null)
    val lastAnalysis: StateFlow<AnalysisResult?> = _lastAnalysis.asStateFlow()

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _analyzerReady = MutableStateFlow(false)
    val analyzerReady: StateFlow<Boolean> = _analyzerReady.asStateFlow()

    /** True when the real Gemma model is loaded; false means the keyword stub is in use. */
    private val _usingGemma = MutableStateFlow(false)
    val usingGemma: StateFlow<Boolean> = _usingGemma.asStateFlow()

    private val _statusLine = MutableStateFlow("")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    /** Where the per-call consent handshake is. */
    private val _consentState = MutableStateFlow(ConsentState.IDLE)
    val consentState: StateFlow<ConsentState> = _consentState.asStateFlow()

    /** True while a call is being protected (a transcript source is/was active this session). */
    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive.asStateFlow()

    fun setConsentState(v: ConsentState) { _consentState.value = v }
    fun setCallActive(v: Boolean) { _callActive.value = v }

    fun setServiceConnected(v: Boolean) { _serviceConnected.value = v }
    fun setSourceApp(v: String?) { _sourceApp.value = v }
    fun setScraping(v: Boolean) { _scraping.value = v }
    fun setTranscript(v: List<TranscriptEntry>) { _transcript.value = v }
    fun setAnalyzerReady(v: Boolean) { _analyzerReady.value = v }
    fun setUsingGemma(v: Boolean) { _usingGemma.value = v }
    fun setStatusLine(v: String) { _statusLine.value = v }
    fun setLastAnalysis(v: AnalysisResult?) { _lastAnalysis.value = v }
    fun setOverlayVisible(v: Boolean) { _overlayVisible.value = v }

    fun setRisk(level: RiskLevel, reason: String, confidence: Float) {
        _riskLevel.value = level
        _riskReason.value = reason
        _riskConfidence.value = confidence
    }

    fun clearSession() {
        _transcript.value = emptyList()
        _riskLevel.value = RiskLevel.LOW
        _riskReason.value = ""
        _riskConfidence.value = 0f
        _lastAnalysis.value = null
        _overlayVisible.value = false
        _sourceApp.value = null
        _scraping.value = false
        _consentState.value = ConsentState.IDLE
        _callActive.value = false
    }
}
