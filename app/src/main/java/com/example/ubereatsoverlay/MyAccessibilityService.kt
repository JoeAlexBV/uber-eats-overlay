package com.example.ubereatsoverlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.Locale

class MyAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Change to TYPE_APPLICATION_OVERLAY for better touch priority on modern Android
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 150

        setupDragListener()
        windowManager.addView(overlayView, params)
    }

    private fun setupDragListener() {
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f

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
                        // Calculate how much the finger has moved since ACTION_DOWN
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        // Update params based on that movement
                        params.x = initialX + deltaX
                        params.y = initialY + deltaY

                        // Apply the new position to the window
                        windowManager.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false // Allow other events to pass if not Down or Move
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.contains("ubercab.driver") == false) return

        // Search windows for the Uber root
        val uberNode = windows.find {
            it.root?.packageName?.contains("ubercab.driver") == true
        }?.root ?: rootInActiveWindow ?: return

        var bestPayout = ""
        var bestDistance = ""
        var bestTime = ""

        var highestPay = 0.0

        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val combined = "$text $desc"

            // 1. PAYOUT: Look for the large dollar amount.
            if (combined.contains("$")) {
                // Regex to find any price pattern like $12.34
                val match = Regex("\\d+\\.\\d+").find(combined)
                match?.value?.toDoubleOrNull()?.let { foundPrice ->
                    // ONLY keep the number if it's the largest one we've seen so far
                    if (foundPrice > highestPay) {
                        highestPay = foundPrice
                        bestPayout = "$$foundPrice" 
                    }
                }
            }

            // 2. DISTANCE: Look for (X.X mi)
            if (combined.contains(" mi")) {
                bestDistance = combined
            }

            // 3. TIME: Look for X min
            if (combined.contains(" min")) {
                bestTime = combined
            }

            for (i in 0 until node.childCount) {
                scan(node.getChild(i))
            }
        }

        scan(uberNode)

        // Only update if we found at least a payout
        if (bestPayout.isNotEmpty()) {
            updateData(bestPayout, bestDistance, bestTime)
        }

        uberNode.recycle()
    }

    private fun updateData(rawPayout: String, rawDistance: String, rawTime: String) {
        try {
            val numberRegex = Regex("\\d+\\.\\d+|\\d+")

            val payout = numberRegex.find(rawPayout)?.value?.toDoubleOrNull() ?: 0.0
            val miles = numberRegex.find(rawDistance)?.value?.toDoubleOrNull() ?: 0.0

            // Extract minutes from "39 min (12.8 mi)"
            val timeMatch = numberRegex.find(rawTime)?.value?.toDoubleOrNull() ?: 0.0
            val totalHours = timeMatch / 60.0

            // Calculation Logic
            val tripCost = miles * ((3.50 / 22.0) + 0.15)
            val netProfit = payout - tripCost
            val perHour = if (totalHours > 0) payout / totalHours else 0.0

            val display = String.format(Locale.US,
                    "Pay: $%.2f\nNet: $%.2f\nRate: $%.2f/hr",
                    payout, netProfit, perHour)

            updateOverlayText(display)
        } catch (e: Exception) {
            Log.e("UberDebug", "Math Error: ${e.message}")
        }
    }

    private fun updateOverlayText(newText: String) {
        overlayView?.let {
            val tv = it.findViewById<TextView>(R.id.overlay_text)
            tv.post { tv.text = newText }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
    }

    override fun onInterrupt() {}
}