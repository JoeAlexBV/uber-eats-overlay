package com.example.ubereatsoverlay

import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Template
import androidx.core.app.Person

object HudDashboard {

    const val ACTION_UPDATE = "com.example.ubereatsoverlay.HUD_DASHBOARD_UPDATE"

    private val driver = Person.Builder()
        .setName("Driver")
        .setKey("driver")
        .build()

    private val hud = Person.Builder()
        .setName("Ranger HUD")
        .setKey("ranger_hud")
        .setBot(true)
        .build()

    fun buildTemplate(onRefresh: () -> Unit): Template {
        val conversation = ConversationItem.Builder()
            .setId("ranger_profit_hud")
            .setTitle(CarText.create(HudState.carNotificationTitle()))
            .setSelf(driver)
            .setMessages(
                listOf(
                    CarMessage.Builder()
                        .setSender(hud)
                        .setBody(CarText.create(HudState.carNotificationText()))
                        .setReceivedTimeEpochMillis(System.currentTimeMillis())
                        .setRead(false)
                        .build()
                )
            )
            .setConversationCallback(
                object : ConversationCallback {
                    override fun onMarkAsRead() = onRefresh()

                    override fun onTextReply(replyText: String) = onRefresh()
                }
            )
            .build()

        val list = ItemList.Builder()
            .addItem(conversation)
            .build()

        return ListTemplate.Builder()
            .setTitle("Ranger HUD")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .setActionStrip(refreshActionStrip(onRefresh))
            .build()
    }

    private fun refreshActionStrip(onRefresh: () -> Unit): ActionStrip {
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener { onRefresh() }
                    .build()
            )
            .build()
    }
}
