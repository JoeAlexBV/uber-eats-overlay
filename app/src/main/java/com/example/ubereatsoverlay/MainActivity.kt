package com.example.ubereatsoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 1. UI Components
    private lateinit var payoutTextView: TextView
    private lateinit var distanceTextView: TextView
    private lateinit var storeTextView: TextView

    // 2. The Receiver Logic
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type")
            val value = intent?.getStringExtra("value")

            when (type) {
                "payout" -> payoutTextView.text = value
                "distance" -> distanceTextView.text = value
                "store" -> storeTextView.text = value
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        payoutTextView = findViewById(R.id.payoutText)
        distanceTextView = findViewById(R.id.distanceText)
        storeTextView = findViewById(R.id.storeText)
    }

    // 3. Register the receiver
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("UBER_DATA_UPDATE")

        // Handle Android 14+ requirements for exported receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(dataReceiver, filter)
        }
    }

    // 4. Unregister to prevent memory leaks
    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
    }
}