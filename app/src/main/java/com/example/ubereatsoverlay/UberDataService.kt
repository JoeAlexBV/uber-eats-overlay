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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.IOException
import java.util.Locale

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var addOnInfoTextView: TextView? = null 
    private var overlayVisible: Boolean = false
    
    private lateinit var params: WindowManager.LayoutParams

    // --- 2023 FORD RANGER COSTS ---
    private val GAS_PRICE_PER_GALLON = 4.10
    private val TRUCK_MPG = 22.0
    private val WEAR_AND_TEAR_PER_MILE = 0.25
    private val COST_PER_MILE = (GAS_PRICE_PER_GALLON / TRUCK_MPG) + WEAR_AND_TEAR_PER_MILE 
    
    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0,
        var isAddOn: Boolean = false 
    )

    // STRICTOR REGEX: Prevents "99.0" noise by requiring specific formats
    private val payRegex = Regex("\\\$(\\d+\\.\\d{2})")              // Matches "$7.19"
    private val distanceRegex = Regex("\\((\\d+\\.?\\d*)\\s*mi\\)") // Matches "(2.3 mi)"
    private val timeRegex = Regex("(\\d+)\\s*min")                  // Matches "17 min"
    
    private var lastNotifiedPay = 0.0
    private var currentActiveTrip: TripOffer? = null 
    private var lastProcessedOfferHash: Int = 0 

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startLoggingToFile()
    }

    private fun startLoggingToFile() {
        val logFile = File(getExternalFilesDir(null), "uber_debug_logs.txt")
        try {
            Runtime.getRuntime().exec("logcat -c")
            Runtime.getRuntime().exec("logcat -f ${logFile.absolutePath} UberDebug:D *:S")
            Log.d("UberDebug", "File logging started at: ${logFile.absolutePath}")
        } catch (e: IOException) {
            Log.e("UberDebug", "Failed to start file logging", e)
        }
    }

    private val CHANNEL_ID = "UberProfitChannel"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Trip Profitability"
            val descriptionText = "Shows Net and Rate for Uber offers"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendProfitNotification(net: Double, rate: Double, isAddOn: Boolean = false, combinedRate: Double = 0.0) {
        val title = if (isAddOn) "Add-on Offer Found" else "New Offer Found"
        val content = if (isAddOn) {
            String.format(Locale.US, "Add-on Net: $%.2f | Rate: $%.2f/hr | Combined: $%.2f/hr", net, rate, combinedRate)
        } else {
            String.format(Locale.US, "Net: $%.2f | Rate: $%.2f/hr", net, rate)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) 
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(1000, 1000))
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))

        try {
            if (Math.abs(net - lastNotifiedPay) > 0.01 || isAddOn) {
                NotificationManagerCompat.from(this).notify(1, builder.build())
                lastNotifiedPay = net
            }
        } catch (e: SecurityException) {
            Log.e("UberDebug", "Missing Notification Permission")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkgName = event.packageName?.toString() ?: ""
        
        if (pkgName.contains("ubercab.driver")) {
            val rootNode = rootInActiveWindow ?: return
            val currentOffer = TripOffer()
            collectTripData(rootNode, currentOffer)
            
            val currentOfferHash = currentOffer.pay.hashCode() + currentOffer.distance.hashCode() + currentOffer.time.hashCode()

            // VALIDATION GATE: Ignore junk data like the "99.0" ratings or battery levels
            if (currentOffer.pay in 1.50..85.00 && currentOffer.distance > 0.1) { 
                if (currentOfferHash != lastProcessedOfferHash) {
                    lastProcessedOfferHash = currentOfferHash

                    if (currentActiveTrip != null && currentOffer.isAddOn) {
                        Log.d("UberDebug", "Detected Add-on: $currentOffer")
                    } else {
                        Log.d("UberDebug", "Detected Primary: $currentOffer")
                        currentActiveTrip = currentOffer.copy()
                    }

                    showOverlay()
                    updateUI(currentOffer)
                }
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // If the window changes significantly and no offer is found, cleanup
                removeOverlay()
                lastProcessedOfferHash = 0
            }
            rootNode.recycle()
        }
    }

    private fun collectTripData(node: AccessibilityNodeInfo?, currentOffer: TripOffer) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"

        // Trigger Add-on detection
        if (combined.contains("Add to trip", true) || combined.contains("Next trip", true)) {
            currentOffer.isAddOn = true
        }

        // 1. Extract Pay (Requires specific $XX.XX format)
        payRegex.find(combined)?.let {
            val payValue = it.groupValues[1].toDoubleOrNull() ?: 0.0
            if (payValue > currentOffer.pay) { // Keep the highest value found on screen
                currentOffer.pay = payValue
                Log.d("UberDebug", "Scraped Pay: $payValue")
            }
        }

        // 2. Extract Distance (Requires (X.X mi) format seen in screenshots)
        distanceRegex.find(combined)?.let {
            val distValue = it.groupValues[1].toDoubleOrNull() ?: 0.0
            currentOffer.distance = distValue
            Log.d("UberDebug", "Scraped Distance: $distValue")
        }

        // 3. Extract Time
        timeRegex.find(combined)?.let {
            val timeValue = it.groupValues[1].toDoubleOrNull() ?: 0.0
            currentOffer.time = timeValue
            Log.d("UberDebug", "Scraped Time: $timeValue")
        }

        for (i in 0 until node.childCount) {
            collectTripData(node.getChild(i), currentOffer)
        }
    }

    private fun updateUI(offer: TripOffer) {
        val netPay = offer.pay - (offer.distance * COST_PER_MILE)
        val hourlyRate = if (offer.time > 0) netPay / (offer.time / 60.0) else 0.0

        val displayStr = StringBuilder()
            .append(String.format(Locale.US, "Pay: $%.2f\n", offer.pay))
            .append(String.format(Locale.US, "Net: $%.2f\n", netPay))
            .append(String.format(Locale.US, "Rate: $%.2f/hr", hourlyRate))

        var combinedRateForNotification = 0.0

        if (offer.isAddOn && currentActiveTrip != null) {
            val currentActiveNet = currentActiveTrip!!.pay - (currentActiveTrip!!.distance * COST_PER_MILE)
            val combinedNet = (currentActiveTrip!!.pay + offer.pay) - ((currentActiveTrip!!.distance + offer.distance) * COST_PER_MILE)
            val combinedHours = (currentActiveTrip!!.time + offer.time) / 60.0
            val combinedRate = if (combinedHours > 0) combinedNet / combinedHours else 0.0
            combinedRateForNotification = combinedRate

            addOnInfoTextView?.post {
                addOnInfoTextView?.text = String.format(Locale.US, "Total Rate: $%.2f/hr", combinedRate)
                addOnInfoTextView?.visibility = View.VISIBLE
            }
        } else {
            addOnInfoTextView?.post { addOnInfoTextView?.visibility = View.GONE }
        }

        infoTextView?.post { infoTextView?.text = displayStr }
        sendProfitNotification(netPay, hourlyRate, offer.isAddOn, combinedRateForNotification)
    }

    private fun showOverlay() {
        if (!overlayVisible) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // GOD MODE: TYPE_ACCESSIBILITY_OVERLAY stays above system pop-ups
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 150
            }

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            infoTextView = overlayView?.findViewById(R.id.overlay_text)
            addOnInfoTextView = overlayView?.findViewById(R.id.overlay_addon_text)

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
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}