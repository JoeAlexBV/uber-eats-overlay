package com.example.ubereatsoverlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class UberDataService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 1. Filter for Uber Driver App and Window Changes
        if (event.packageName?.toString() == "com.ubercab.driver") {
            
            // 2. Get the root node of the screen
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // 3. Search for specific text found in your screenshot/logs
                findDeliveryData(rootNode)
                rootNode.recycle()
            }
        }
    }

    private fun findDeliveryData(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString()
        if (text != null) {
            // Identify Payout (Look for the $ symbol)
            if (text.contains("$")) {
                broadcastData("payout", text)
            }
            // Identify Distance (Look for "mi" or "km")
            else if (text.contains("mi") || text.contains("km")) {
                broadcastData("distance", text)
            }
            // Identify Store (Usually text without special characters)
            else if (text.length > 3 && !text.contains(":")) {
                broadcastData("store", text)
            }
        }

        // Recursively check all children
        for (i in 0 until node.childCount) {
            findDeliveryData(node.getChild(i))
        }
    }

    private fun broadcastData(type: String, value: String) {
        val intent = Intent("UBER_DATA_UPDATE").apply {
            putExtra("type", type)
            putExtra("value", value)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}
}