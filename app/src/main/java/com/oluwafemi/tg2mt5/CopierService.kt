package com.oluwafemi.tg2mt5

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the app alive in the background (with a permanent notification) so Telegram
 * messages keep arriving while the screen is off, and checks MT5 for closed trades.
 */
class CopierService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resultsJob: Job? = null

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
        if (resultsJob?.isActive != true) {
            resultsJob = scope.launch {
                var round = 0
                while (isActive) {
                    try {
                        app.processor.checkResults()
                    } catch (_: Exception) {
                    }
                    // Channel signals are price-checked every 5 minutes.
                    if (round % 5 == 0) {
                        try {
                            app.processor.verifyChannelSignals()
                        } catch (_: Exception) {
                        }
                    }
                    round++
                    delay(RESULT_CHECK_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        /** How often open trades are checked for a TP/SL result. MetaApi API calls are free ($0). */
        const val RESULT_CHECK_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CopierService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CopierService::class.java))
        }
    }
}
