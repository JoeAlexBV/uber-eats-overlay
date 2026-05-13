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
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

class UberDataService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var infoTextView: TextView? = null
    private var addOnInfoTextView: TextView? = null // New TextView for add-on info
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
        var isAddOn: Boolean = false // Flag to indicate if this offer is an add-on
    )

    // Regex for extracting numbers
    private val payRegex = Regex("\\$\\s*(\\d+\\.?\\d*)")
    private val distanceRegex = Regex("(\\d+\\.?\\d*)\\s*mi")
    private val timeRegex = Regex("(\\d+)\\s*min")
    private var lastNotifiedPay = 0.0

    // State variables for managing active trip and offer detection
    private var currentActiveTrip: TripOffer? = null // The trip currently accepted and being driven
    private var lastProcessedOfferHash: Int = 0 // Hash of the last offer data processed to avoid redundant updates

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    private val CHANNEL_ID = "UberProfitChannel"

    private fun createNotificationChannel() {
        // Fixed: Build check and NotificationManager references
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
            String.format(Locale.US, "Add-on Net: $%.2f | Add-on Rate: $%.2f/hr | Combined Rate: $%.2f/hr", net, rate, combinedRate)
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
            // Add a style for longer text if needed
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))

        try {
            // Only notify if the net value has changed significantly, or if it's an add-on
            if (Math.abs(net - lastNotifiedPay) > 0.01 || isAddOn) {
                NotificationManagerCompat.from(this).notify(1, builder.build())
                lastNotifiedPay = net
            }
        } catch (e: SecurityException) {
            // Log if POST_NOTIFICATIONS permission is missing
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Log.d("UberDebug", "Event received from: ${event.packageName}") // Too chatty
        if (event.packageName?.toString()?.contains("ubercab") == true) {
            val rootNode = rootInActiveWindow
            rootNode?.let {
                val currentOffer = TripOffer()
                // Collect potential trip data from the screen
                collectTripData(it, currentOffer)
                
                // Calculate a hash for the scraped offer to detect if it's truly new or changed
                val currentOfferHash = currentOffer.pay.hashCode() + currentOffer.distance.hashCode() + currentOffer.time.hashCode()

                if (currentOffer.pay > 0.0) { // We found a potential offer
                    if (currentOfferHash != lastProcessedOfferHash) { // It's a new or changed offer
                        lastProcessedOfferHash = currentOfferHash

                        // Heuristic for add-on detection:
                        // 1. An active trip is already present.
                        // 2. The current offer card might contain "Add to trip" or similar text.
                        //    (This is handled in collectTripData by setting currentOffer.isAddOn)
                        // 3. The offer's distance/time might be relatively small compared to its pay.
                        //    (This is a weaker heuristic, relying more on UI text is better)
                        if (currentActiveTrip != null && currentOffer.isAddOn) {
                            // This is likely an add-on offer
                            Log.d("UberDataService", "Detected Add-on Offer: $currentOffer")
                        } else if (currentActiveTrip == null && !currentOffer.isAddOn) {
                            // This is a primary offer, and no active trip is set yet.
                            Log.d("UberDataService", "Detected Primary Offer: $currentOffer")
                            // Assume this primary offer becomes the active trip if it's displayed.
                            // This is a simplification; ideally, we'd detect an "Accept" action.
                            currentActiveTrip = currentOffer.copy()
                        } else if (currentActiveTrip != null && !currentOffer.isAddOn) {
                            // This is a primary offer, but an active trip is already set.
                            // This could mean a new primary offer appeared while on an active trip (unlikely for Uber Eats)
                            // or the active trip was completed and a new primary offer appeared.
                            // For now, we'll treat it as a new primary offer, potentially replacing the active trip.
                            Log.d("UberDataService", "Detected New Primary Offer while active trip exists: $currentOffer")
                            currentActiveTrip = currentOffer.copy()
                        }

                        showOverlay()
                        updateUI(currentOffer)
                    }
                } else {
                    // No valid offer found on screen (or offer disappeared)
                    if (overlayVisible) { // Only remove if it was visible
                        Log.d("UberDataService", "No offer detected, removing overlay.")
                        removeOverlay()
                        lastProcessedOfferHash = 0 // Reset hash
                        // If the overlay disappears, it means the offer was either accepted or declined.
                        // If it was a primary offer, and it disappeared, we should clear currentActiveTrip
                        // unless we have a way to detect it was accepted.
                        // For now, let's clear it to avoid stale active trip data.
                        currentActiveTrip = null
                    }
                }
                it.recycle()
            }
        } else {
            // If the Uber app is no longer in the foreground, remove the overlay and clear active trip
            if (overlayVisible) {
                Log.d("UberDataService", "Uber app not in foreground, removing overlay.")
                removeOverlay()
                currentActiveTrip = null
                lastProcessedOfferHash = 0
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
        val combined = "$text $desc".trim()

        // Check for add-on indicators within the node's text/description
        if (combined.contains("Add to trip", ignoreCase = true) ||
            combined.contains("Next trip", ignoreCase = true) ||
            combined.contains("Stack", ignoreCase = true) ||
            combined.contains("additional trip", ignoreCase = true)) { // Common Uber Eats add-on phrases
            currentOffer.isAddOn = true
            // Log.d("UberDataService", "Add-on indicator found: $combined") // Too chatty
        }

        // 1. Extract Pay (e.g., "$5.02")
        if (combined.contains("$") &&
            !combined.lowercase().contains("expect") && // Avoid "expected earnings"
            !combined.lowercase().contains("include")) { // Avoid "includes tips"
            payRegex.find(combined)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                currentOffer.pay = it
                // Log.d("UberDebug", "Scraped Pay: $it from '$combined'") // Too chatty
            }
        }
        // 2. Extract Distance (e.g., "9.1 mi")
        if (combined.contains("mi")) {
            distanceRegex.find(combined)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                currentOffer.distance = it
                // Log.d("UberDebug", "Scraped Distance: $it from '$combined'") // Too chatty
            }
        }
        // 3. Extract Time (e.g., "25 min")
        if (combined.contains("min")) {
            timeRegex.find(combined)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                currentOffer.time = it
                // Log.d("UberDebug", "Scraped Time: $it from '$combined'") // Too chatty
            }
        }

        for (i in 0 until node.childCount) {
            collectTripData(node.getChild(i), currentOffer)
        }
    }

    private fun updateUI(offer: TripOffer) {
        val netPay = offer.pay - (offer.distance * COST_PER_MILE)
        val totalHours = offer.time / 60.0
        val hourlyRate = if (totalHours > 0) netPay / totalHours else 0.0

        val displayStr = StringBuilder()
            .append(String.format(java.util.Locale.US, "Pay: $%.2f\n", offer.pay))

        var combinedRateForNotification = 0.0

        if (offer.isAddOn && currentActiveTrip != null) {
            // This is an add-on offer, calculate its impact on the active trip
            val currentActiveNet = currentActiveTrip!!.pay - (currentActiveTrip!!.distance * COST_PER_MILE)
            val currentActiveHours = currentActiveTrip!!.time / 60.0
            val currentActiveRate = if (currentActiveHours > 0) currentActiveNet / currentActiveHours else 0.0

            // Calculate combined trip details if this add-on is accepted
            val combinedPay = currentActiveTrip!!.pay + offer.pay
            val combinedDistance = currentActiveTrip!!.distance + offer.distance
            val combinedTime = currentActiveTrip!!.time + offer.time
            val combinedNet = combinedPay - (combinedDistance * COST_PER_MILE)
            val combinedHours = combinedTime / 60.0
            val combinedRate = if (combinedHours > 0) combinedNet / combinedHours else 0.0
            combinedRateForNotification = combinedRate

            val rateChange = combinedRate - currentActiveRate

            displayStr.append(String.format(Locale.US, "Net: $%.2f\n", netPay)) // Net for the add-on itself
            displayStr.append(String.format(Locale.US, "Rate: $%.2f/hr\n", hourlyRate)) // Rate for the add-on itself

            // Update the add-on specific TextView
            addOnInfoTextView?.post {
                addOnInfoTextView?.text = String.format(Locale.US, "Overall Rate: $%.2f/hr (%+.2f)", combinedRate, rateChange)
                addOnInfoTextView?.visibility = View.VISIBLE
            }
            Log.d("UberDataService", "Add-on UI updated. Combined Rate: $combinedRate, Change: $rateChange")

        } else {
            // This is a primary offer or no active trip is set
            displayStr.append(String.format(Locale.US, "Net: $%.2f\n", netPay))
            displayStr.append(String.format(Locale.US, "Rate: $%.2f/hr", hourlyRate))

            // Hide the add-on specific TextView
            addOnInfoTextView?.post {
                addOnInfoTextView?.visibility = View.GONE
            }
            Log.d("UberDataService", "Primary UI updated. Net: $netPay, Rate: $hourlyRate")
        }

        infoTextView?.post { // Ensure UI updates happen on the main thread
            infoTextView?.text = displayStr
        }

        // Send to Android Auto / Phone Notification
        sendProfitNotification(netPay, hourlyRate, offer.isAddOn, combinedRateForNotification)
    }

    private fun showOverlay() {
        if (!overlayVisible) {
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // Correct type for accessibility services
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
            addOnInfoTextView = overlayView?.findViewById(R.id.overlay_addon_text) // Initialize new TextView

            // ADD THE DRAG LOGIC HERE
            setupDragListener()

            windowManager?.addView(overlayView, params)
            overlayVisible = true
            Log.d("UberDataService", "Overlay shown.")
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
            addOnInfoTextView = null // Clear reference
            overlayVisible = false
            Log.d("UberDataService", "Overlay removed.")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        currentActiveTrip = null // Clear active trip on service destroy
        lastProcessedOfferHash = 0
        Log.d("UberDataService", "Service destroyed.")
    }
}