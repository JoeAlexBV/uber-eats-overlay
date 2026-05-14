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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import java.io.File
import java.io.IOException
import java.util.Locale

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var overlayVisible: Boolean = false
    private lateinit var params: WindowManager.LayoutParams

    companion object {
        var currentNet: Double = 0.0
        var currentRate: Double = 0.0
        var isScanning: Boolean = false
    }

    // --- 2023 FORD RANGER COSTS ---
    private val COST_PER_MILE = (4.10 / 19.0) + 0.25 
    // 4.10 is the average cost of a gallon of regular in the US as of mid-2026, 
    // and 19 is the average miles per gallon for a Ford Ranger. 
    // The $0.25 accounts for additional wear and tear costs.
    
    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0,
        var isAddOn: Boolean = false,
        var isRealOffer: Boolean = false 
    )

    private val payRegex = Regex("\\\$\\s*(\\d+\\.?\\d*)")
    private val distanceRegex = Regex("(\\d+\\.?\\d*)\\s*mi\\b")
    private val timeRegex = Regex("(\\d+)\\s*min\\b")
    
    private var lastProcessedOfferHash: Int = 0 

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startLoggingToFile()
        
        // ALWAYS ON: Fire the first notification immediately so the truck tile populates
        showOverlay()
        sendProfitNotification(0.0, 0.0, false, true) 
        Log.d("UberDebug", "Service Connected. Always-On Tile Activated.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val currentOffer = TripOffer()
        
        // Scan all windows to catch pop-ups
        for (window in windows) {
            if (window.root?.packageName?.contains("ubercab.driver") == true) {
                collectTripData(window.root, currentOffer)
            }
        }

        val isHistoryOrActive = combinedCheck(rootInActiveWindow, listOf("history", "earnings", "details", "navigate"))

        if (currentOffer.pay > 0.1 && !isHistoryOrActive) {
            val currentOfferHash = (currentOffer.pay * 100).toInt() + (currentOffer.distance * 10).toInt()
            if (currentOfferHash != lastProcessedOfferHash) {
                lastProcessedOfferHash = currentOfferHash
                updateUI(currentOffer)
            }
        } else if (isHistoryOrActive) {
            currentNet = 0.0
            currentRate = 0.0
            updateOverlayText("Ready for orders...\n(System Standby)")
        }
    }

    private fun collectTripData(node: AccessibilityNodeInfo?, currentOffer: TripOffer) {
        if (node == null) return
        val combined = "${node.text} ${node.contentDescription}".lowercase()

        if (combined.contains("accept") || combined.contains("match") || combined.contains("exclusive")) {
            currentOffer.isRealOffer = true
        }

        // HIGHEST PAY PRIORITY: Prevents $0.00 overwrite
        payRegex.find(combined)?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: 0.0
            if (value > 0.1 && value > currentOffer.pay && !combined.contains("extra for")) {
                currentOffer.pay = value
            }
        }

        distanceRegex.find(combined)?.let { currentOffer.distance = it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        timeRegex.find(combined)?.let { currentOffer.time = it.groupValues[1].toDoubleOrNull() ?: 0.0 }

        for (i in 0 until node.childCount) collectTripData(node.getChild(i), currentOffer)
    }

    private fun updateUI(offer: TripOffer) {
        currentNet = offer.pay - (offer.distance * COST_PER_MILE)
        currentRate = if (offer.time > 0) currentNet / (offer.time / 60.0) else 0.0

        val displayStr = String.format(Locale.US, "Pay: $%.2f\nNet: $%.2f\nRate: $%.2f/hr", offer.pay, currentNet, currentRate)
        updateOverlayText(displayStr)
        
        // Push update to the truck
        sendProfitNotification(currentNet, currentRate, offer.isAddOn, false)
    }

    private fun sendProfitNotification(net: Double, rate: Double, isAddOn: Boolean, isInitial: Boolean) {
        val sender = Person.Builder().setName("Uber Tracker").setBot(true).build()
        
        val content = if (isInitial) "Ready for orders..." 
                      else String.format(Locale.US, "Net: $%.2f | Rate: $%.2f/hr", net, rate)

        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(content, System.currentTimeMillis(), sender)
            .setConversationTitle("Profit Status")

        val builder = NotificationCompat.Builder(this, "UberProfitChannel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOngoing(true) // THIS KEEPS IT ON THE DASHBOARD
            .setOnlyAlertOnce(true) // Stops the truck from 'dinging' every time the price changes
            .setAutoCancel(false)

        try {
            NotificationManagerCompat.from(this).notify(1, builder.build())
        } catch (e: SecurityException) {}
    }

    // ... (Keep your showOverlay, setupDragListener, and combinedCheck functions here)
    private fun updateOverlayText(text: String) { infoTextView?.post { infoTextView?.text = text } }
    
    private fun showOverlay() {
        if (!overlayVisible) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 150 }
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            infoTextView = overlayView?.findViewById(R.id.overlay_text)
            setupDragListener()
            windowManager?.addView(overlayView, params)
            overlayVisible = true
        }
    }

    private fun setupDragListener() {
        overlayView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                params.x = event.rawX.toInt() - 150; params.y = event.rawY.toInt() - 150
                windowManager?.updateViewLayout(overlayView, params); true
            } else false
        }
    }

    private fun startLoggingToFile() {
        val logFile = File(getExternalFilesDir(null), "uber_debug_logs.txt")
        try { Runtime.getRuntime().exec("logcat -c"); Runtime.getRuntime().exec("logcat -f ${logFile.absolutePath} UberDebug:D *:S") } catch (e: IOException) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("UberProfitChannel", "Profitability", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onInterrupt() {}
}