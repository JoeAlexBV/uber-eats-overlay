package com.example.ubereatsoverlay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var payoutTextView: TextView
    private lateinit var distanceTextView: TextView

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusTextView.text = if (granted) {
            getString(R.string.hud_status_ready)
        } else {
            getString(R.string.hud_status_no_notif_permission)
        }
    }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHudPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusText)
        payoutTextView = findViewById(R.id.payoutText)
        distanceTextView = findViewById(R.id.distanceText)

        findViewById<Button>(R.id.btnTestReady).setOnClickListener {
            runTest { HudState.applyIdle() }
        }
        findViewById<Button>(R.id.btnTestOffer7).setOnClickListener {
            runTest { HudState.applyTestOffer(RangerEconomics.Offer(7.03, 3.2, 18.0)) }
        }
        findViewById<Button>(R.id.btnTestOffer14).setOnClickListener {
            runTest { HudState.applyTestOffer(RangerEconomics.Offer(14.06, 5.1, 25.0)) }
        }

        HudForeground.ensureChannel(this)
        refreshHudPreview()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(HudDashboard.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(dataReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dataReceiver, filter)
        }
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
    }

    private fun runTest(apply: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        apply()
        HudState.publishUpdate(this)
        refreshHudPreview()
        Toast.makeText(this, R.string.toast_test_sent_dashboard, Toast.LENGTH_LONG).show()
    }

    private fun updatePermissionStatus() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        statusTextView.text = if (granted) {
            getString(R.string.hud_status_ready)
        } else {
            getString(R.string.hud_status_no_notif_permission)
        }
    }

    private fun refreshHudPreview() {
        payoutTextView.text = HudState.formatPhoneOverlay()
        distanceTextView.text = getString(R.string.dhu_dashboard_hint)
    }
}
