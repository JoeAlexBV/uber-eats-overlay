package com.example.ubereatsoverlay

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import androidx.core.app.Person
import java.io.IOException
import java.util.Locale

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var addOnInfoTextView: TextView? = null 
    private var overlayVisible: Boolean = false
    private lateinit var params: WindowManager.LayoutParams

    companion object {
        // Shared variable so the CarAppService can read the latest stats
        var latestStats: String = "Waiting for offer..."
        var currentNet: Double = 0.0
        var currentRate: Double = 0.0
    }

    // --- 2023 FORD RANGER COSTS ---
    private val GAS_PRICE = 4.10
    private val MPG = 22.0
    private val MAINTENANCE = 0.25
    private val TOTAL_COST_PER_MILE = (GAS_PRICE / MPG) + MAINTENANCE 
    
    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0,
        var isAddOn: Boolean = false,
        var isRealOffer: Boolean = false 
    )

    // Regex for specific formats seen in image_1dadfd.jpg
    private val payRegex = Regex("\\\$\\s*(\\d+\\.?\\d*)")
    private val distanceRegex = Regex("(\\d+\\.?\\d*)\\s*mi")
    private val timeRegex = Regex("(\\d+)\\s*min")
    
    private var lastProcessedOfferHash: Int = 0 

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startLoggingToFile()
        showOverlay() // Show immediately on start
        updateOverlayText("Ready for orders...")
        Log.d("UberDebug", "Service Connected. Cost/Mile: $TOTAL_COST_PER_MILE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkgName = event.packageName?.toString() ?: ""

        // Prioritize event.source for immediate pop-ups
        val rootNode = event.source ?: rootInActiveWindow ?: windows.find { 
            it.root?.packageName?.contains("ubercab.driver") == true
        }?.root

        if (rootNode == null) return

        val currentOffer = TripOffer()
        collectTripData(rootNode, currentOffer)
        
        // Safety logic: Only show if it's an offer, not an earnings summary
        val isLikelyOffer = currentOffer.isRealOffer && currentOffer.pay > 1.0 && currentOffer.distance > 0.0
        val isHistoryOrActive = combinedCheck(rootNode, listOf("history", "earnings", "details", "navigate"))

        if (isLikelyOffer && !isHistoryOrActive) {
            val currentOfferHash = (currentOffer.pay * 100).toInt() + (currentOffer.distance * 10).toInt()
            
            if (currentOfferHash != lastProcessedOfferHash) {
                lastProcessedOfferHash = currentOfferHash
                showOverlay()
                updateUI(currentOffer)
            }
        }

        if (rootNode != event.source) {
            rootNode.recycle()
        }
    }

    private fun combinedCheck(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false
        val combined = "${node.text} ${node.contentDescription}".lowercase()
        if (keywords.any { combined.contains(it) }) return true
        for (i in 0 until node.childCount) {
            if (combinedCheck(node.getChild(i), keywords)) return true
        }
        return false
    }

    private fun collectTripData(node: AccessibilityNodeInfo?, currentOffer: TripOffer) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc".lowercase()

        // 1. TRIGGER CHECK: Detect actual offer screen
        if (combined.contains("accept") || combined.contains("match") || 
            combined.contains("exclusive") || combined.contains("delivery")) { 
            currentOffer.isRealOffer = true
        }

        if (combined.contains("add to trip") || combined.contains("next trip")) {
            currentOffer.isAddOn = true
        }

        // 2. SCRAPE PAY with "Quest Filter"
        // If the text contains Quest keywords like "extra for" or "trips", ignore it
        if (combined.contains("$") && !combined.contains("extra for") && !combined.contains("trips")) {
            payRegex.find(combined)?.let {
                Log.d("UberDebug", "Potential Pay Found: ${it.value} in text: [$combined]")
                val value = it.groupValues[1].toDoubleOrNull() ?: 0.0
                // Keep the largest dollar amount found on screen (the fare)
                if (value > currentOffer.pay) currentOffer.pay = value
            }
        }

        // 3. SCRAPE DISTANCE (e.g., "3.0 mi")
        distanceRegex.find(combined)?.let {
            currentOffer.distance = it.groupValues[1].toDoubleOrNull() ?: 0.0
        }

        // 4. SCRAPE TIME (e.g., "22 min")
        timeRegex.find(combined)?.let {
            currentOffer.time = it.groupValues[1].toDoubleOrNull() ?: 0.0
        }

        for (i in 0 until node.childCount) {
            collectTripData(node.getChild(i), currentOffer)
        }
    }

    private fun updateUI(offer: TripOffer) {
        val netPay = offer.pay - (offer.distance * TOTAL_COST_PER_MILE)
        val hourlyRate = if (offer.time > 0) netPay / (offer.time / 60.0) else 0.0

        currentNet = netPay
        currentRate = hourlyRate

        val displayStr = String.format(Locale.US, "Pay: $%.2f\nNet: $%.2f\nRate: $%.2f/hr", 
            offer.pay, netPay, hourlyRate)
        
        latestStats = displayStr
        updateOverlayText(displayStr)
        sendProfitNotification(netPay, hourlyRate, offer.isAddOn)
    }

    private fun updateOverlayText(text: String) {
        infoTextView?.post { infoTextView?.text = text }
    }

    private fun showOverlay() {
        if (!overlayVisible) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 150 
            }

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            infoTextView = overlayView?.findViewById(R.id.overlay_text)
            
            setupDragListener()
            if (overlayView?.parent != null) windowManager?.removeView(overlayView)
            windowManager?.addView(overlayView, params)
            overlayVisible = true
        }
    }

    private fun setupDragListener() {
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

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
            overlayVisible = false
            lastProcessedOfferHash = 0
        }
    }

    private fun startLoggingToFile() {
        val logFile = File(getExternalFilesDir(null), "uber_debug_logs.txt")
        try {
            Runtime.getRuntime().exec("logcat -c")
            Runtime.getRuntime().exec("logcat -f ${logFile.absolutePath} UberDebug:D *:S")
        } catch (e: IOException) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("UberProfitChannel", "Profitability", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun sendProfitNotification(net: Double, rate: Double, isAddOn: Boolean) {
        val user = Person.Builder().setName("Uber Data").build()
        val content = String.format(Locale.US, "Net: $%.2f | Rate: $%.2f/hr", net, rate)

        val builder = NotificationCompat.Builder(this, "UberProfitChannel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // This Style is the "key" to the 1/4 dashboard tile
            .setStyle(NotificationCompat.MessagingStyle(user)
                .addMessage(content, System.currentTimeMillis(), user)
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(1, builder.build())
        } catch (e: SecurityException) {}
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}