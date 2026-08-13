package com.audimus.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.audimus.core.AnalysisPipeline
import com.audimus.core.AudimusBridge
import com.audimus.core.ProtectionState
import com.audimus.scrape.TranscriptScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Audimus accessibility service — reads the on-screen transcript of a transcription app (e.g.
 * Live Transcribe) via the accessibility node tree and feeds the text to [AnalysisPipeline].
 *
 * No audio is captured. Reading on-screen text is the standard, benign use of an accessibility
 * service (the same capability a screen reader uses), which is why this approach has no audio,
 * mic-silencing, speakerphone, or privacy concerns.
 */
class AudimusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AudimusA11yService"

        /** Packages whose on-screen text Audimus treats as a live transcript. */
        private val TRANSCRIPT_SOURCES = mapOf(
            "com.google.audio.hearing.visualization.accessibility.scribe" to "Live Transcribe",
            // Pixel Live Caption is rendered by Android System Intelligence; its caption text is
            // exposed in the node tree of an OVERLAY window (not the focused app's window).
            "com.google.android.as" to "Live Caption",
            "com.android.chrome" to "Chrome (captions)", // e.g. live captions / demo pages
        )

        /** Caption apps frequently emit several accessibility events for one visual update. */
        private const val SCRAPE_DEBOUNCE_MS = 350L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var pipeline: AnalysisPipeline
    private lateinit var overlay: RiskOverlay
    private val scraper = TranscriptScraper()

    /** The transcript-source package currently being scraped, so we can reset on a new source. */
    private var activeSourcePkg: String? = null
    private var scrapeJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ProtectionState.setServiceConnected(true)

        pipeline = AnalysisPipeline(this, scope)
        overlay = RiskOverlay(this).apply { setOnDismiss { pipeline.dismissOverlay() } }
        AudimusBridge.pipeline = pipeline

        scraper.onNewText = { text -> pipeline.appendUtterance(text) }

        scope.launch { pipeline.initialize() }
        scope.launch {
            ProtectionState.overlayVisible.collectLatest { visible ->
                if (visible) overlay.show() else overlay.hide()
            }
        }
        Log.i(TAG, "Audimus accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // An event can arrive before onServiceConnected() finishes wiring the pipeline.
        if (!::pipeline.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        val sourceLabel = TRANSCRIPT_SOURCES[pkg] ?: return

        // New transcript source (e.g. a fresh call): reset the diff so we don't compare this
        // call's text against the previous one and swallow its opening lines.
        if (pkg != activeSourcePkg) {
            activeSourcePkg = pkg
            scraper.reset()
        }

        ProtectionState.setSourceApp(sourceLabel)
        ProtectionState.setScraping(true)
        pipeline.setSource(sourceLabel)
        // Caption apps can emit a burst of events while revising one line. Wait for the burst to
        // settle, then traverse the node tree once instead of once per event.
        scrapeJob?.cancel()
        scrapeJob = scope.launch {
            delay(SCRAPE_DEBOUNCE_MS)
            if (activeSourcePkg != pkg) return@launch
            // The transcript may live in an overlay window (Live Caption) that is NOT
            // rootInActiveWindow, so locate the source window and fall back to the active one.
            val root = rootForPackage(pkg) ?: rootInActiveWindow
            runCatching { scraper.scrape(root) }
                .onFailure { Log.w(TAG, "Transcript scrape failed for $pkg", it) }
        }
    }

    /**
     * Finds the root node of the on-screen window owned by [pkg]. Uses [getWindows] (enabled by
     * flagRetrieveInteractiveWindows) so overlay windows — e.g. Live Caption, drawn on top of the
     * focused app — are reachable even though [rootInActiveWindow] only returns the focused app.
     */
    private fun rootForPackage(pkg: String): android.view.accessibility.AccessibilityNodeInfo? {
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) return r
            }
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        scrapeJob?.cancel()
        ProtectionState.setServiceConnected(false)
        AudimusBridge.pipeline = null
        runCatching { pipeline.shutdown() }
        runCatching { overlay.destroy() }
        scope.cancel()
    }
}
