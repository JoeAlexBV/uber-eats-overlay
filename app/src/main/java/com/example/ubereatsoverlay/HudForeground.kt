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
    private const val CAR_TILE_NOTIFICATION_ID = 1338
    private const val SERVICE_CHANNEL_ID = "ranger_hud_service"
    private const val CAR_TILE_CHANNEL_ID = "ranger_hud_car_tile"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Ranger HUD service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps profit scraping active while driving"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)
        }
        if (manager.getNotificationChannel(CAR_TILE_CHANNEL_ID) == null) {
            val tileChannel = NotificationChannel(
                CAR_TILE_CHANNEL_ID,
                "Ranger HUD car tile",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows the current delivery HUD in Android Auto"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(tileChannel)
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
        RangerHudMediaService.refresh()
    }

    fun postCarTile(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(CAR_TILE_NOTIFICATION_ID, buildCarTileNotification(context))
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
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
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

    private fun buildCarTileNotification(context: Context): android.app.Notification {
        val driver = Person.Builder()
            .setName("Driver")
            .build()
        val hud = Person.Builder()
            .setName("Ranger HUD")
            .setBot(true)
            .build()
        val message = "${HudState.tileTitle()}\n${HudState.tileText()}"
        val style = NotificationCompat.MessagingStyle(driver)
            .setConversationTitle("Ranger Profit HUD")
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message,
                    System.currentTimeMillis(),
                    hud
                )
            )

        return NotificationCompat.Builder(context, CAR_TILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ranger)
            .setContentTitle(HudState.tileTitle())
            .setContentText(HudState.tileText())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .addAction(markReadAction(context))
            .addAction(replyAction(context))
            .build()
    }

    private fun markReadAction(context: Context): NotificationCompat.Action {
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
            "Read",
            pendingIntent
        ).build()
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
            .setAllowGeneratedReplies(false)
            .build()
    }
}
