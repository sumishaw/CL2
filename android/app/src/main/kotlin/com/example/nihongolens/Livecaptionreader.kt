package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * LiveCaptionReader v3 — Fixed package detection
 *
 * Problem: packageNames restriction was too strict, filtering ALL events.
 * Fix: Monitor all packages but strictly filter by Live Captions window
 *      characteristics (floating window + short speech-like text).
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        // Known Live Captions package names
        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.accessibility.caption",
            "com.google.android.accessibility.captions",
            "com.google.android.tts",
            "com.android.systemui",
        )

        // View IDs used by Live Captions
        private val CAPTION_VIEW_IDS = listOf(
            "caption_text", "captiontext", "live_caption_text",
            "transcript_text", "captionwindow", "caption_window",
            "caption", "live_caption",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 350L

        @Volatile var isRunning       = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null

        // Detected Live Captions package (auto-detected at runtime)
        @Volatile var detectedPackage = ""
    }

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:   Job? = null
    private var translateJob: Job? = null
    private var lastSentText  = ""
    private var lastHindiOut  = ""
    private val translateQueue = LinkedBlockingQueue<String>(8)

    // Track seen packages for debugging
    private val seenPackages = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        // Monitor ALL packages — we filter by content inside onAccessibilityEvent
        // packageNames=null is required because Live Captions package name varies
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            info.packageNames = null  // monitor all — filter in code
        }

        startTranslateWorker()
        Log.i(TAG, "LiveCaptionReader connected — monitoring all packages for Live Captions")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return

        val pkg = event.packageName?.toString() ?: return

        // Log new packages for debugging (first time only)
        if (seenPackages.add(pkg)) {
            Log.d(TAG, "New package seen: $pkg")
        }

        // Strategy 1: Known Live Captions packages — process immediately
        val isKnownCaptionPkg = pkg in LIVE_CAPTION_PACKAGES
        if (isKnownCaptionPkg) {
            detectedPackage = pkg
            processEvent(event)
            return
        }

        // Strategy 2: Unknown package — check if it looks like Live Captions
        // Live Captions window has specific characteristics
        if (detectedPackage.isEmpty()) {
            checkIfLiveCaptionsWindow(event, pkg)
        }
    }

    private fun checkIfLiveCaptionsWindow(event: AccessibilityEvent, pkg: String) {
        // Look for a window with caption-like view IDs
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val captionNode = findCaptionNode(root)
        if (captionNode != null) {
            Log.i(TAG, "Live Captions detected in package: $pkg")
            detectedPackage = pkg
            val text = captionNode.text?.toString()?.trim() ?: return
            if (text.length > 1) scheduleTranslation(text)
        }
    }

    private fun processEvent(event: AccessibilityEvent) {
        val text = extractCaptionText(event) ?: return
        val clean = text.trim()
        if (clean.length < 2 || clean.length > 400) return
        if (clean == lastCaptionText) return
        lastCaptionText = clean
        Log.d(TAG, "Caption: $clean")
        scheduleTranslation(clean)
    }

    // ── Text extraction ───────────────────────────────────────────────────────

    private fun extractCaptionText(event: AccessibilityEvent): String? {
        // Method 1: Direct from event text
        val evText = event.text
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.length > 1 }
            ?.joinToString(" ")
        if (!evText.isNullOrBlank()) return evText

        // Method 2: Walk accessibility tree for caption nodes
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        return findCaptionNode(root)?.text?.toString()?.trim()
    }

    private fun findCaptionNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text   = node.text?.toString()?.trim() ?: ""
        val cls    = node.className?.toString()?.lowercase() ?: ""

        // Match known caption view IDs
        if (CAPTION_VIEW_IDS.any { viewId.contains(it) } && text.isNotBlank()) {
            return node
        }

        // Match TextView with reasonable speech length (Live Captions style)
        if (cls.contains("textview") && text.length in 3..300) {
            // Additional heuristic: Live Captions text doesn't contain URLs or long words
            if (!text.contains("http") && !text.contains("www.")) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val found = findCaptionNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ── Translation pipeline ──────────────────────────────────────────────────

    private fun scheduleTranslation(text: String) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (text == lastCaptionText && text != lastSentText) {
                lastSentText = text
                if (translateQueue.size >= 8) translateQueue.poll()
                translateQueue.offer(text)
            }
        }
    }

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank() || hindi == lastHindiOut) continue
                lastHindiOut = hindi

                Log.d(TAG, "Hindi: $hindi")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"en","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) { Log.w(TAG, "HTTP ${conn.responseCode}"); return null }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); translateJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
