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
    private val COST_PER_MILE = (4.10 / 22.0) + 0.25 
    
    private data class TripOffer(
        var pay: Double = 0.0,
        var distance: Double = 0.0,
        var time: Double = 0.0,
        var isAddOn: Boolean = false,
        var isRealOffer: Boolean = false // New flag to trigger only on offers
    )

    // Require the $ sign to avoid grabbing random numbers from URLs or dates
    private val payRegex = Regex("\\\$\\s*(\\d+\\.?\\d*)")
    // Use \b (word boundary) to ensure "mi" doesn't match the start of "min"
    private val distanceRegex = Regex("(\\d+\\.?\\d*)\\s*mi\\b")
    private val timeRegex = Regex("(\\d+)\\s*min\\b")
    
    private var lastNotifiedPay = 0.0
    private var currentActiveTrip: TripOffer? = null 
    private var lastProcessedOfferHash: Int = 0 

    // Calculations for the 2023 Ford Ranger
    private val GAS_PRICE = 4.10
    private val MPG = 22.0
    private val MAINTENANCE = 0.25
    private val TOTAL_COST_PER_MILE = (GAS_PRICE / MPG) + MAINTENANCE

    override fun onCreate() {
        super.onCreate()
        Log.d("UberDebug", "UberDataService onCreate called.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startLoggingToFile()
        Log.d("UberDebug", "UberDataService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkgName = event.packageName?.toString() ?: ""
        Log.d("UberDebug", "Event: ${AccessibilityEvent.eventTypeToString(event.eventType)}, Package: $pkgName")
        
        // Only process events from the Uber Driver app
        if (!pkgName.contains("ubercab.driver")) {
            // If we're no longer in the Uber app, remove the overlay
            if (overlayVisible) {
                Log.d("UberDebug", "Uber app not in foreground ($pkgName), removing overlay.")
                removeOverlay()
                currentActiveTrip = null // Clear active trip if we leave Uber
                currentActiveTrip = null
            }
            return
        }

        // Fallback: Search all windows if rootInActiveWindow is null or incorrect
        val rootNode = rootInActiveWindow ?: windows.find { 
        // Prioritize event.source for immediate pop-up data, fallback to window root
        val rootNode = event.source ?: rootInActiveWindow ?: windows.find { 
            it.root?.packageName?.contains("ubercab.driver") == true
        }?.root

        if (rootNode == null) {
            Log.w("UberDebug", "No root node found for Uber app in event: ${AccessibilityEvent.eventTypeToString(event.eventType)}")
            return // Cannot proceed without a root node
        }
        Log.d("UberDebug", "Root node found for Uber app. Class: ${rootNode.className}")

        val currentOffer = TripOffer()
        
        collectTripData(rootNode, currentOffer)
        
        // ONLY SHOW if we see an "Accept", "Match", or "Exclusive" button.
        // This prevents the overlay from showing on the Earnings or Trip Details screens.
        // Note: Check your logs to see if 'isRealOffer' is ever true. 
        // If not, Uber might be using an image or a different word for the button.
        if ((currentOffer.isRealOffer || currentOffer.pay > 0.0) && currentOffer.distance > 0.0) { 
        // logic: Show if it looks like an offer, HIDE if it looks like history or an active trip
        val isLikelyOffer = (currentOffer.isRealOffer || currentOffer.pay > 0.1) && currentOffer.distance > 0.1
        val isHistoryOrActive = combinedCheck(rootNode, listOf("history", "earnings", "details", "navigate", "way to"))

        if (isLikelyOffer && !isHistoryOrActive) {
            val currentOfferHash = (currentOffer.pay * 100).toInt() + (currentOffer.distance * 10).toInt()
            
            if (currentOfferHash != lastProcessedOfferHash) {
                lastProcessedOfferHash = currentOfferHash
                showOverlay()
                // Ensure overlayView is not null before trying to update UI
                if (overlayView != null) {
                    updateUI(currentOffer)
                } else {
                    Log.e("UberDebug", "Overlay view is null after showOverlay(), cannot update UI.")
                }
            }
        } else {
            // Hide overlay if we aren't looking at an active offer
            if (overlayVisible) { // Only remove if it's actually visible
                Log.d("UberDebug", "Not a real offer or data incomplete, hiding overlay.")
                removeOverlay()
            }
        } else if (isHistoryOrActive) {
            removeOverlay()
        }

        // Only recycle if it's not the event.source, as event.source is recycled by the system
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
        
        // --- VERBOSE LOGGING: THIS IS CRUCIAL FOR DEBUGGING ---
        // Uncomment the line below to see every piece of text the service encounters.
        // This will be very chatty, but invaluable for identifying what Uber's UI looks like.
        // Log.d("UberDebug_Verbose", "Node Text: '$text', Desc: '$desc', Combined: '$combined'")

        // TRIGGER CHECK: Is this an actual offer?
        if (combined.contains("accept") || 
            combined.contains("match") || 
            combined.contains("exclusive") ||
            combined.contains("opportunity")) { // Added opportunity as a common keyword
            combined.contains("opportunity") ||
            combined.contains("delivery")) { 
            currentOffer.isRealOffer = true
            Log.d("UberDebug", "Found 'Accept/Match/Exclusive' keyword, setting isRealOffer = true.")
        }

        if (combined.contains("add to trip") || combined.contains("next trip")) {
            currentOffer.isAddOn = true
            Log.d("UberDebug", "Found 'add to trip/next trip' keyword, setting isAddOn = true.")
        }

        // SCRAPE PAY: Use combined (text + desc) as some amounts are in labels
        payRegex.find(combined)?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: 0.0
            // Only update if the found value is greater than current (to get the main offer pay)
            // and is a reasonable pay amount (e.g., not a tiny tip or a huge daily total)
            if (value > currentOffer.pay && value < 500.0) { // Adjusted max pay for more flexibility
                currentOffer.pay = value
                Log.d("UberDebug", "Scraped Pay: $value from '$combined'")
            }
        }

        // SCRAPE DISTANCE: Distance is almost always in Content Description
        distanceRegex.find(combined)?.let {
            val dist = it.groupValues[1].toDoubleOrNull() ?: 0.0
            currentOffer.distance = dist
            Log.d("UberDebug", "Scraped Distance: $dist from '$combined'")
        }

        // SCRAPE TIME: Time is also often in Content Description
        timeRegex.find(combined)?.let {
            val t = it.groupValues[1].toDoubleOrNull() ?: 0.0
            currentOffer.time = t
            Log.d("UberDebug", "Scraped Time: $t from '$combined'")
        }

        for (i in 0 until node.childCount) {
            collectTripData(node.getChild(i), currentOffer)
        }
    }

    private fun updateUI(offer: TripOffer) {
        val netPay = offer.pay - (offer.distance * COST_PER_MILE)
        val hourlyRate = if (offer.time > 0) netPay / (offer.time / 60.0) else 0.0

        val displayStr = String.format(Locale.US, "Pay: $%.2f\nNet: $%.2f\nRate: $%.2f/hr", 
            offer.pay, netPay, hourlyRate)

        infoTextView?.post { infoTextView?.text = displayStr }
        
        // Android Auto Notification
        sendProfitNotification(netPay, hourlyRate, offer.isAddOn)
    }

    private fun showOverlay() {
        if (!overlayVisible) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or // Crucial for drag
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100 
            }

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)
            infoTextView = overlayView?.findViewById(R.id.overlay_text)
            addOnInfoTextView = overlayView?.findViewById(R.id.overlay_addon_text) // Ensure this is initialized

            windowManager?.addView(overlayView, params)
            Log.d("UberDebug", "Overlay added to WindowManager.")
            overlayVisible = true
            setupDragListener() // Call the drag listener setup here
        }
    }

    private fun removeOverlay() {
        if (overlayVisible && overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
            overlayVisible = false
            Log.d("UberDebug", "Overlay removed from WindowManager.")
            lastProcessedOfferHash = 0
        }
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
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false // Allow other events to pass if not Down or Move
            }
        })
    }

    // --- LOGGING & NOTIFICATIONS ---
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
        val content = String.format(Locale.US, "Net: $%.2f | Rate: $%.2f/hr", net, rate)
        val builder = NotificationCompat.Builder(this, "UberProfitChannel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isAddOn) "Add-on Profit" else "Trip Profit")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(1, builder.build())
        } catch (e: SecurityException) {}
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        Log.d("UberDebug", "UberDataService onDestroy called.")
    }
}