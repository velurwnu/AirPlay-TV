package com.amusic.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the AirPlay receiver when the TV boots (and after an app update), if allowed in settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Settings(context).background) runCatching { ReceiverService.start(context) }
    }
}
