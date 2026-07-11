package com.audimus.core

/**
 * Tiny process-singleton bridge so the dashboard's "simulate transcript" input can feed text into
 * the [AnalysisPipeline] that lives inside the accessibility service. Null when the service is off.
 */
object AudimusBridge {
    @Volatile
    var pipeline: AnalysisPipeline? = null
}
