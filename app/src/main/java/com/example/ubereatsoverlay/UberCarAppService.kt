package com.example.ubereatsoverlay

import android.content.Intent
import androidx.car.app.CarAppService
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
        // Create a "Message" containing your Uber data
        val message = Message.Builder("Net: $${currentNet} | Rate: $${currentRate}/hr")
            .setSender(Person.Builder().setName("Uber Offer").build())
            .build()

        // Use a ListTemplate with the Messaging category to fit the small tile
        return ListTemplate.Builder()
            .setSingleList(
                ItemList.Builder()
                    .addItem(
                        Row.Builder()
                            .setTitle("New Uber Offer")
                            .addText("Net Profit: $${currentNet}")
                            .build()
                    ).build()
            )
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}