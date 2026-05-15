package com.example.ubereatsoverlay

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

object HudDashboard {

    const val ACTION_UPDATE = "com.example.ubereatsoverlay.HUD_DASHBOARD_UPDATE"

    /**
     * One message block — no list rows, no scrolling. Fits 1/4 and 1/2 host panes.
     */
    fun buildTemplate(onRefresh: () -> Unit): Template {
        return MessageTemplate.Builder(HudState.formatCompactBlock())
            .setTitle(HudState.statusTitle())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Refresh")
                            .setOnClickListener { onRefresh() }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
