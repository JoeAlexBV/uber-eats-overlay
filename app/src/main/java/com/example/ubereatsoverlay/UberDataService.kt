package com.example.ubereatsoverlay

import android.accessibilityservice.AccessibilityService
import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.provider.Settings
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var addOnTextView: TextView? = null // FIXED: Added this
    private var overlayVisible: Boolean = false
    private lateinit var params: WindowManager.LayoutParams

    companion object {
        private const val TAG = "UberDebug"
        private const val UBER_PACKAGE = "com.ubercab.driver"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val OFFER_DEDUP_MS = 120_000L
        private const val EVENT_LOG_THROTTLE_MS = 5_000L
        private const val MAX_DEBUG_LOG_BYTES = 512 * 1024

        // Shared stats for the Ford Ranger SYNC display
        var currentNet: Double 
            get() = HudState.net
            set(_) {}
        var currentRate: Double 
            get() = HudState.rate
            set(_) {}
        var latestStats: String 
            get() = HudState.formatPhoneOverlay()
            set(_) {}

        private val GHOST_PAY_KEYWORDS = listOf("quest", "promotion", "bonus", "extra for", "trips")
        private val PAST_TRIP_KEYWORDS = listOf("trip details", "order details", "summary")
        private val EARNINGS_KEYWORDS = listOf("earnings", "wallet", "history")
        private val NAV_KEYWORDS = listOf("navigate", "searching", "go offline")
        private val LIVE_OFFER_KEYWORDS = listOf(
            "accept",
            "delivery",
            "exclusive",
            "offer",
            "request",
            "pickup",
            "dropoff",
            "uber eats"
        )
        private val NOISE_TEXT_MARKERS = listOf(
            "map?",
            "cloudfront",
            "marker=",
            "anchorx",
            "width=",
            "height=",
            "%24"
        )
    }

    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0,
        var isLiveOffer: Boolean = false,
        var isAddOn: Boolean = false // Added for the yellow text
    )

    private val payRegex = Regex("\\\$\\s*(\\d+\\.?\\d*)")
    private val distanceRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:mi|mile|miles)\\b")
    private val timeRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:min|mins|minute|minutes)\\b")
    private val logTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var lastHash: Int = Int.MIN_VALUE
    private var lastIdlePushTime = 0L
    private var lastOfferAlertTime = 0L
    private var lastEventLogTime = 0L
    private var carHandshakeComplete = false
    private var debugLogFile: File? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        HudForeground.ensureChannel(this)
        startLoggingToFile()
        logDebug("service_connected overlay=${canDrawOverlay()} notifications=${hasNotificationPermission()}")
        HudForeground.start(this)
        showOverlay()

        // Sync with SYNC 4 dashboard
        commitDashboard { HudState.applyIdle() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRelevantEvent(event)) return

        val trip = TripOffer()
        collectTripData(event, trip)

        // Multi-window scan to catch Uber pop-ups
        val scanSystemUi = event.packageName?.toString() == SYSTEM_UI_PACKAGE || event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        for (window in windows) {
            val root = window.root ?: continue
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName.contains(UBER_PACKAGE) || (scanSystemUi && packageName.contains(SYSTEM_UI_PACKAGE))) {
                collectTripData(root, trip)
            }
            root.recycle()
        }

        // Logic check for screen type
        val isPastTrip = matchesKeywords(PAST_TRIP_KEYWORDS)
        val isEarnings = matchesKeywords(EARNINGS_KEYWORDS) || matchesKeywords(GHOST_PAY_KEYWORDS)
        
        when {
            trip.isLiveOffer && trip.pay > 0.1 && !isEarnings -> {
                val alerting = shouldAlertOffer(trip)
                logDebug("live_offer pay=${trip.pay} mi=${trip.distance} min=${trip.time} addOn=${trip.isAddOn} alerting=$alerting")
                commitOffer(trip, alerting) { HudState.applyLiveOffer(it) }
            }
            isEarnings -> commitDashboard { HudState.applyStandby() }
            else -> {
                // Throttle idle updates to keep SYNC 4 tile stable
                val now = System.currentTimeMillis()
                if (now - lastIdlePushTime > 30000) {
                    lastIdlePushTime = now
                    commitDashboard { HudState.applyIdle() }
                }
            }
        }
    }

    private fun collectTripData(event: AccessibilityEvent, trip: TripOffer) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            maybeLogEvent(event)
            return
        }

        val textParts = mutableListOf<String>()
        event.text?.mapNotNullTo(textParts) { it?.toString() }
        event.contentDescription?.toString()?.let(textParts::add)

        val notification = event.parcelableData as? Notification
        val extras = notification?.extras
        if (extras != null) {
            listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_SUMMARY_TEXT,
                Notification.EXTRA_INFO_TEXT
            ).forEach { key ->
                extras.getCharSequence(key)?.toString()?.let(textParts::add)
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNullTo(textParts) { it?.toString() }
        }

        textParts.forEach { collectTripData(it, trip) }
        logDebug("notification_event package=${event.packageName} text='${textParts.joinToString(" | ").take(240)}' parsed=${trip.summary()}")
    }

    private fun collectTripData(node: AccessibilityNodeInfo?, trip: TripOffer) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        collectTripData(text, trip)
        collectTripData(desc, trip)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTripData(child, trip)
            child?.recycle()
        }
    }

    private fun collectTripData(rawText: String, trip: TripOffer) {
        if (rawText.isBlank()) return
        val combined = rawText.lowercase(Locale.US)
        val isNoise = NOISE_TEXT_MARKERS.any { combined.contains(it) }

        if (combined.contains("accept") || combined.contains("exclusive") || combined.contains("delivery")) {
            trip.isLiveOffer = true
        }
        if (LIVE_OFFER_KEYWORDS.any { combined.contains(it) } &&
            !PAST_TRIP_KEYWORDS.any { combined.contains(it) } &&
            !EARNINGS_KEYWORDS.any { combined.contains(it) }
        ) {
            trip.isLiveOffer = true
        }

        // Detect Add-on/Stacked orders
        if (combined.contains("add to trip") || combined.contains("next trip")) {
            trip.isAddOn = true
        }

        // SCRAPE PAY: Priority to highest found value (kills the $0.00 bug)
        if (!isNoise && combined.contains("$") && !GHOST_PAY_KEYWORDS.any { combined.contains(it) }) {
            payRegex.findAll(combined).forEach {
                val value = it.groupValues[1].toDoubleOrNull() ?: 0.0
                if (value in 0.1..200.0 && value > trip.pay) trip.pay = value
            }
        }

        distanceRegex.find(combined)?.let { trip.distance = it.groupValues[1].toDoubleOrNull() ?: trip.distance }
        timeRegex.find(combined)?.let { trip.time = it.groupValues[1].toDoubleOrNull() ?: trip.time }
    }

    private fun updateOverlayText(mainText: String) {
        infoTextView?.post {
            infoTextView?.text = mainText
            
            // Handle the Yellow Add-on text visibility
            if (mainText.contains("+") || mainText.contains("Add-on")) {
                addOnTextView?.visibility = View.VISIBLE
                addOnTextView?.text = getString(R.string.stacked_order_alert)
            } else {
                addOnTextView?.visibility = View.GONE
            }

            // FORCE re-measure of the window to prevent clipping
            if (overlayVisible && overlayView != null) {
                try {
                    // Update params to wrap content again to catch the new size
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    windowManager?.updateViewLayout(overlayView, params)
                } catch (e: Exception) {}
            }
        }
    }

    private fun showOverlay() {
        if (!overlayVisible) {
            if (!canDrawOverlay()) {
                logDebug("overlay_skipped missing SYSTEM_ALERT_WINDOW permission")
                return
            }

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 150
            }

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            
            // FIXED: Initialize BOTH text views
            infoTextView = overlayView?.findViewById(R.id.overlay_text)
            addOnTextView = overlayView?.findViewById(R.id.overlay_addon_text)
            
            setupDragListener()
            try {
                windowManager?.addView(overlayView, params)
                overlayVisible = true
                logDebug("overlay_added")
            } catch (e: Exception) {
                logDebug("overlay_failed ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun setupDragListener() {
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (e: Exception) {
                            logDebug("Update layout failed during drag: ${e.message}")
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // ... (Keep your commitOffer, commitDashboard, and publish as they were)

    private fun commitOffer(trip: TripOffer, alerting: Boolean, apply: (RangerEconomics.Offer) -> Unit) {
        val offer = RangerEconomics.Offer(trip.pay, trip.distance, trip.time)
        apply(offer)
        publish()

        // High-priority alert to trigger Android Auto Heads-Up display
        if (alerting) {
            HudForeground.sendAlert(
                this,
                getString(R.string.new_offer_title),
                HudState.formatPhoneOverlay()
            )
            logDebug("alert_sent ${HudState.formatPhoneOverlay().replace('\n', ' ')}")
        }
    }

    private fun commitDashboard(apply: () -> Unit) {
        apply()
        publish()
    }

    private fun publish() {
        updateOverlayText(HudState.formatPhoneOverlay())
        HudForeground.refresh(this)
    }

    private fun startLoggingToFile() {
        val logFile = File(getExternalFilesDir(null), "uber_debug_logs.txt")
        debugLogFile = logFile
        try {
            if (logFile.length() > MAX_DEBUG_LOG_BYTES) {
                logFile.writeText("Debug log rotated at ${logTimestampFormat.format(Date())}\n")
            }
            logFile.appendText("\n--- Ranger HUD session ${logTimestampFormat.format(Date())} ---\n")
        } catch (e: IOException) {
            Log.e(TAG, "Unable to initialize debug log: ${e.message}")
        }
    }

    private fun matchesKeywords(keywords: List<String>): Boolean {
        val activeRoot = rootInActiveWindow
        if (combinedCheck(activeRoot, keywords)) {
            activeRoot?.recycle()
            return true
        }
        activeRoot?.recycle()

        val currentWindows = windows
        for (window in currentWindows) {
            val root = window.root
            if (combinedCheck(root, keywords)) {
                root?.recycle()
                return true
            }
            root?.recycle()
        }
        return false
    }

    private fun combinedCheck(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc".lowercase()
        
        if (keywords.any { combined.contains(it) }) return true
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (combinedCheck(child, keywords)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }
        return false
    }

    private fun isRelevantEvent(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.contains(UBER_PACKAGE)) return true
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return true
        if (packageName.contains(SYSTEM_UI_PACKAGE)) {
            return event.text.any { part ->
                val text = part?.toString()?.lowercase(Locale.US).orEmpty()
                text.contains("uber") || text.contains("delivery") || text.contains("$")
            } || event.contentDescription?.toString()?.lowercase(Locale.US)?.let {
                it.contains("uber") || it.contains("delivery") || it.contains("$")
            } == true
        }
        return false
    }

    private fun maybeLogEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastEventLogTime < EVENT_LOG_THROTTLE_MS) return
        lastEventLogTime = now
        logDebug("event type=${AccessibilityEvent.eventTypeToString(event.eventType)} package=${event.packageName} text='${event.text.joinToString(" | ").take(160)}'")
    }

    private fun shouldAlertOffer(trip: TripOffer): Boolean {
        val offerHash = listOf(
            (trip.pay * 100).toInt(),
            (trip.distance * 10).toInt(),
            trip.time.toInt(),
            trip.isAddOn
        ).hashCode()
        val now = System.currentTimeMillis()
        if (offerHash == lastHash && now - lastOfferAlertTime < OFFER_DEDUP_MS) {
            return false
        }
        lastHash = offerHash
        lastOfferAlertTime = now
        return true
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun logDebug(message: String) {
        Log.d(TAG, message)
        try {
            debugLogFile?.appendText("${logTimestampFormat.format(Date())} $message\n")
        } catch (e: IOException) {
            Log.e(TAG, "Unable to write debug log: ${e.message}")
        }
    }

    private fun TripOffer.summary(): String =
        "live=$isLiveOffer pay=$pay mi=$distance min=$time addOn=$isAddOn"

    override fun onInterrupt() {
        logDebug("service_interrupted")
    }
}
