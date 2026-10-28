package com.oluwafemi.tg2mt5

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the copier after the phone reboots, if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as App
        if (app.prefs.copierEnabled) {
            try {
                CopierService.start(context)
            } catch (e: Exception) {
                app.log.add("error", "Could not restart copier after reboot", e.toString())
            }
        }
    }
}
