package com.example.ubereatsoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class UberCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen = HudMainScreen(carContext)
        }
    }
}

class HudMainScreen(carContext: CarContext) : Screen(carContext) {

    private var lastInvalidateTime = 0L

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            if (now - lastInvalidateTime >= THROTTLE_MS) {
                lastInvalidateTime = now
                invalidate()
            }
        }
    }

    private val tickHandler = Handler(Looper.getMainLooper())
    private val dashboardTick = object : Runnable {
        override fun run() {
            invalidate()
            tickHandler.postDelayed(this, DASHBOARD_REFRESH_MS)
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            val filter = IntentFilter(HudDashboard.ACTION_UPDATE)
            ContextCompat.registerReceiver(carContext, refreshReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            tickHandler.removeCallbacks(dashboardTick)
            tickHandler.post(dashboardTick)
        }

        override fun onStop(owner: LifecycleOwner) {
            tickHandler.removeCallbacks(dashboardTick)
            carContext.unregisterReceiver(refreshReceiver)
        }
    }

    init {
        lifecycle.addObserver(lifecycleObserver)
    }

    override fun onGetTemplate(): Template {
        return HudDashboard.buildTemplate { invalidate() }
    }

    companion object {
        private const val DASHBOARD_REFRESH_MS = 15_000L
        private const val THROTTLE_MS = 2_000L
    }
}
