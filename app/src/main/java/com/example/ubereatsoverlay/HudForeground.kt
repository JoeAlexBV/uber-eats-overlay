package com.example.ubereatsoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

object HudForeground {

    const val NOTIFICATION_ID = 1337
    const val ALERT_ID = 1338
    private const val CHANNEL_ID = "ranger_hud_service"
    private const val ALERT_CHANNEL_ID = "ranger_hud_alerts"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
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

        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alerts for new delivery offers"
                setShowBadge(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun start(service: Service) {
        ensureChannel(service)
        val notification = buildServiceNotification(service, HudState.formatPhoneOverlay())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
        postCarTile(service)
    }

    fun refresh(service: Service) {
        ensureChannel(service)
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildServiceNotification(service, HudState.formatPhoneOverlay()))
        postCarTile(service)
    }

    fun postCarTile(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(ALERT_ID, buildCarMessageNotification(context, alerting = false))
    }

    fun sendAlert(context: Context, title: String, message: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(ALERT_ID, buildCarMessageNotification(context, title, message, alerting = true))
    }

    private fun buildServiceNotification(
        context: Context,
        summary: String
    ): android.app.Notification {
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

    private fun buildCarMessageNotification(
        context: Context,
        fallbackTitle: String = HudState.carNotificationTitle(),
        fallbackText: String = HudState.carNotificationText(),
        alerting: Boolean
    ): android.app.Notification {
        val title = HudState.carNotificationTitle().ifBlank { fallbackTitle }
        val text = HudState.carNotificationText().ifBlank { fallbackText }
        val driver = Person.Builder()
            .setName("Driver")
            .build()
        val hud = Person.Builder()
            .setName("Ranger HUD")
            .setBot(true)
            .build()
        val style = NotificationCompat.MessagingStyle(driver)
            .setConversationTitle(title)
            .setGroupConversation(false)
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    text,
                    System.currentTimeMillis(),
                    hud
                )
            )

        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ranger)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!alerting)
            .setSilent(!alerting)
            .setAutoCancel(false)
            .setShowWhen(false)
            .addInvisibleAction(markAsReadAction(context))
            .addAction(replyAction(context))
            .build()
    }

    private fun markAsReadAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, HudMessageActionService::class.java)
            .setAction(HudMessageActionService.ACTION_MARK_READ)
        val pendingIntent = PendingIntent.getService(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_action_mark_read,
            "Refresh",
            pendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun replyAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, HudMessageActionService::class.java)
            .setAction(HudMessageActionService.ACTION_REPLY)
        val pendingIntent = PendingIntent.getService(
            context,
            11,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(HudMessageActionService.REMOTE_INPUT_KEY)
            .setLabel("Reply")
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_action_reply,
            "Reply",
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .build()
    }
}
