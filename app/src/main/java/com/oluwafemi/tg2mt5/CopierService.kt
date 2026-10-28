package com.oluwafemi.tg2mt5

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive in the background (with a permanent notification) so Telegram
 * messages keep arriving while the screen is off.
 */
class CopierService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        val text = if (app.prefs.isAuto) "Auto-trade mode" else "Alert-only mode"
        val n = Notifier.service(this, "$text · ${app.prefs.channelIds.size} chat(s) watched")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.SERVICE_ID, n)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tg2mt5:copier").apply { setReferenceCounted(false); acquire() }
        }
        try {
            app.telegram.start()
        } catch (e: Throwable) {
            app.log.add("error", "Telegram could not start", e.toString())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CopierService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CopierService::class.java))
        }
    }
}
