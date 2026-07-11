package com.audimus.analysis

import com.audimus.model.AnalysisResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over "look at the running call transcript and tell me if it's a scam
 * (plus any meeting / task worth surfacing)".
 *
 * Two implementations exist:
 *  - [GemmaCallAnalyzer] runs an on-device LLM (LiteRT-LM / Gemma) and is the real analyzer.
 *  - [StubCallAnalyzer] is a deterministic keyword fallback that needs no model, so the app
 *    is fully testable before the multi-GB model file is pushed to the device.
 *
 * Callers should treat [analyze] as best-effort: a `null` result means "no usable answer
 * this pass" and must never crash the pipeline.
 */
interface CallAnalyzer {

    /** `true` once the analyzer is loaded and able to serve [analyze] calls. */
    val isReady: StateFlow<Boolean>

    /**
     * Loads any backing resources (e.g. the LLM engine). Safe to call more than once.
     *
     * @return `true` if the analyzer is ready afterwards, `false` if it is unavailable
     *   (for example the model file is missing). Never throws for the "unavailable" case.
     */
    suspend fun initialize(): Boolean

    /**
     * Runs one analysis pass over [transcript] (the whole running transcript window).
     *
     * @return an [AnalysisResult], or `null` on failure / malformed model output. Never throws.
     */
    suspend fun analyze(transcript: String): AnalysisResult?

    /** Releases backing resources. Idempotent; safe to call even if never initialized. */
    fun close()
}
