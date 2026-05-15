package com.example.ubereatsoverlay

import android.accessibilityservice.AccessibilityService
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
import java.util.Locale

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var addOnTextView: TextView? = null // FIXED: Added this
    private var overlayVisible: Boolean = false
    private lateinit var params: WindowManager.LayoutParams

    companion object {
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
    }

    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0,
        var isLiveOffer: Boolean = false,
        var isAddOn: Boolean = false // Added for the yellow text
    )

    private val payRegex = Regex("\\\$\\s*(\\d+\\.?\\d*)")
    private val distanceRegex = Regex("(\\d+\\.?\\d*)\\s*mi\\b")
    private val timeRegex = Regex("(\\d+)\\s*min\\b")

    private var lastHash: Int = Int.MIN_VALUE
    private var lastIdlePushTime = 0L
    private var carHandshakeComplete = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        HudForeground.ensureChannel(this)
        startLoggingToFile()
        showOverlay()

        // Sync with SYNC 4 dashboard
        commitDashboard { HudState.applyIdle() }
        HudForeground.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val trip = TripOffer()
        val currentWindows = windows
        
        // Multi-window scan to catch Uber pop-ups
        for (window in currentWindows) {
            val root = window.root ?: continue
            if (root.packageName?.contains("ubercab.driver") == true) {
                collectTripData(root, trip)
            }
            root.recycle()
        }

        // Logic check for screen type
        val isPastTrip = matchesKeywords(PAST_TRIP_KEYWORDS)
        val isEarnings = matchesKeywords(EARNINGS_KEYWORDS) || matchesKeywords(GHOST_PAY_KEYWORDS)
        
        when {
            trip.isLiveOffer && trip.pay > 0.1 && !isEarnings -> {
                commitOffer(trip) { HudState.applyLiveOffer(it) }
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

    private fun collectTripData(node: AccessibilityNodeInfo?, trip: TripOffer) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc".lowercase()

        if (combined.contains("accept") || combined.contains("exclusive") || combined.contains("delivery")) {
            trip.isLiveOffer = true
        }

        // Detect Add-on/Stacked orders
        if (combined.contains("add to trip") || combined.contains("next trip")) {
            trip.isAddOn = true
        }

        // SCRAPE PAY: Priority to highest found value (kills the $0.00 bug)
        if (combined.contains("$") && !GHOST_PAY_KEYWORDS.any { combined.contains(it) }) {
            payRegex.find(combined)?.let {
                val value = it.groupValues[1].toDoubleOrNull() ?: 0.0
                if (value > trip.pay) trip.pay = value
            }
        }

        distanceRegex.find(combined)?.let { trip.distance = it.groupValues[1].toDoubleOrNull() ?: trip.distance }
        timeRegex.find(combined)?.let { trip.time = it.groupValues[1].toDoubleOrNull() ?: trip.time }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTripData(child, trip)
            child?.recycle()
        }
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
            windowManager?.addView(overlayView, params)
            overlayVisible = true
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
                            Log.e("UberDebug", "Update layout failed during drag: ${e.message}")
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // ... (Keep your commitOffer, commitDashboard, and publish as they were)

    private fun commitOffer(trip: TripOffer, apply: (RangerEconomics.Offer) -> Unit) {
        val offer = RangerEconomics.Offer(trip.pay, trip.distance, trip.time)
        apply(offer)
        publish()

        // High-priority alert to trigger Android Auto Heads-Up display
        HudForeground.sendAlert(
            this,
            getString(R.string.new_offer_title),
            HudState.formatPhoneOverlay()
        )
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
        try {
            Runtime.getRuntime().exec("logcat -c")
            Runtime.getRuntime().exec("logcat -f ${logFile.absolutePath} UberDebug:D *:S")
        } catch (e: IOException) {}
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

    override fun onInterrupt() {}
}
