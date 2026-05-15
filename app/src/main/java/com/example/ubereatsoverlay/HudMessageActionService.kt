package com.example.ubereatsoverlay

import android.app.IntentService
import android.content.Intent
import androidx.core.app.RemoteInput

@Suppress("DEPRECATION")
class HudMessageActionService : IntentService("HudMessageActionService") {

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_REPLY -> {
                RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)
                HudForeground.postCarTile(this)
            }
            ACTION_MARK_READ -> HudForeground.postCarTile(this)
        }
    }

    companion object {
        const val ACTION_REPLY = "com.example.ubereatsoverlay.HUD_REPLY"
        const val ACTION_MARK_READ = "com.example.ubereatsoverlay.HUD_MARK_READ"
        const val REMOTE_INPUT_KEY = "hud_reply"
    }
}
