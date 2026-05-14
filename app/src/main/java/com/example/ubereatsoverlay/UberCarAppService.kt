package com.example.ubereatsoverlay

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.model.*
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator

class UberCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

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
        val displayInfo = if (UberDataService.currentNet == 0.0) {
            "Ready for orders..."
        } else {
            String.format(Locale.US, "Net: $%.2f | Rate: $%.2f/hr", 
                UberDataService.currentNet, UberDataService.currentRate)
        }

        val row = Row.Builder()
            .setTitle("Uber Profit Tracker")
            .addText(displayInfo)
            .build()

        return PaneTemplate.Builder(Pane.Builder().addRow(row).build())
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}