package com.example.ubereatsoverlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var overlayVisible: Boolean = false
    
    // Move params to a class property so the listener can modify it
    private lateinit var params: WindowManager.LayoutParams

    // --- YOUR 2023 FORD RANGER COSTS ---
    private val GAS_PRICE_PER_GALLON = 4.10
    private val TRUCK_MPG = 22.0
    private val WEAR_AND_TEAR_PER_MILE = 0.25
    
    // Calculates to roughly ~$0.436 per mile
    private val COST_PER_MILE = (GAS_PRICE_PER_GALLON / TRUCK_MPG) + WEAR_AND_TEAR_PER_MILE 
    
    // Data class to hold extracted trip information
    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0
    )

    // Regex for extracting numbers
    private val numberRegex = Regex("\\d+\\.\\d+|\\d+")

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() == "com.ubercab.driver") {
            val rootNode = rootInActiveWindow
            rootNode?.let {
                val currentOffer = TripOffer()
                // Collect potential trip data from the screen
                collectTripData(it, currentOffer)

                // Only update UI if we found a valid pay amount
                if (currentOffer.pay > 0.0) {
                    showOverlay()
                    updateUI(currentOffer)
                } else {
                    // If no valid offer is found, hide the overlay
                    removeOverlay()
                }
                it.recycle()
            }
        }
    }

    /**
     * Recursively traverses the AccessibilityNodeInfo tree to find trip data.
     * It prioritizes the last found value for each field, assuming it's the most relevant.
     */
    private fun collectTripData(node: AccessibilityNodeInfo?, currentOffer: TripOffer) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"

        // 1. Extract Pay (e.g., "$5.02")
        if (combined.contains("$") &&
            !combined.lowercase().contains("expect") && // Avoid "expected earnings"
            !combined.lowercase().contains("include")) { // Avoid "includes tips"
            val match = Regex("\\$\\d+\\.\\d+").find(combined)
            match?.value?.replace("$", "")?.toDoubleOrNull()?.let {
                currentOffer.pay = it
            }
        }
        // 2. Extract Distance (e.g., "9.1 mi")
        if (combined.contains("mi")) {
            numberRegex.find(combined)?.value?.toDoubleOrNull()?.let {
                currentOffer.distance = it
            }
        }
        // 3. Extract Time (e.g., "25 min")
        if (combined.contains("min")) {
            numberRegex.find(combined)?.value?.toDoubleOrNull()?.let {
                currentOffer.time = it
            }
        }

        for (i in 0 until node.childCount) {
            collectTripData(node.getChild(i), currentOffer)
        }
    }

    private fun updateUI(offer: TripOffer) {
        // Calculate your actual take-home profit
        val netPay = offer.pay - (offer.distance * COST_PER_MILE)
        
        // Calculate the hourly rate based on your net profit
        // Convert minutes to hours for hourly rate calculation
        val hourlyRate = if (offer.time > 0) (netPay / offer.time) * 60 else 0.0

        val displayStr = StringBuilder()
            .append(String.format(java.util.Locale.US, "Pay: $%.2f\n", offer.pay))
            .append(String.format(java.util.Locale.US, "Net: $%.2f\n", netPay))
            .append(String.format(java.util.Locale.US, "Rate: $%.2f/hr", hourlyRate))
            .toString()

        infoTextView?.post { // Ensure UI updates happen on the main thread
            infoTextView?.text = displayStr
        }
    }

    private fun showOverlay() {
        if (!overlayVisible) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            infoTextView = overlayView?.findViewById(R.id.overlay_text)

            // ADD THE DRAG LOGIC HERE
            setupDragListener()

            windowManager?.addView(overlayView, params)
            overlayVisible = true
        }
    }

    private fun setupDragListener() {
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        
                        // Update the window position in real-time
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun removeOverlay() {
        if (overlayVisible && overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
            infoTextView = null
            overlayVisible = false
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}