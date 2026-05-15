package com.example.ubereatsoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Minimal ongoing notification — keeps the accessibility FGS alive on Android 14+.
 * The dashboard UI lives in the Car App (PaneTemplate), not in messaging notifications.
 */
object HudForeground {

    const val NOTIFICATION_ID = 1337
    private const val CHANNEL_ID = "ranger_hud_service"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ranger HUD service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps profit scraping active while driving"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun start(service: Service) {
        ensureChannel(service)
        val notification = build(service, HudState.formatPhoneOverlay())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun refresh(service: Service) {
        ensureChannel(service)
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, build(service, HudState.formatPhoneOverlay()))
    }

    private fun build(context: Context, summary: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ranger)
            .setContentTitle("Ranger Profit HUD")
            .setContentText(summary)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
