package com.example.ubereatsoverlay

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.model.*
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

class UberCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return UberCarScreen(carContext)
            }
        }
    }
}

class UberCarScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val row = Row.Builder()
            .setTitle("Uber Profit Tracker")
            .addText(
                String.format(
                    "Net: $%.2f | Rate: $%.2f/hr",
                    UberDataService.currentNet,
                    UberDataService.currentRate
                )
            )
            .build()

        val pane = Pane.Builder()
            .addRow(row)
            .build()

        // PaneTemplate is resizable and fits better in dashboard tiles
        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}