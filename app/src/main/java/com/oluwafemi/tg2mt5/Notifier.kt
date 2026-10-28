package com.oluwafemi.tg2mt5

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    // A new channel id: Android keeps a channel's sound/vibration fixed once created, so the old "signals" channel is replaced.
    const val CH_SIGNALS = "signals_vibrate"
    const val CH_SERVICE = "service"
    const val SERVICE_ID = 1

    /** Three strong buzzes: wait 0 ms, buzz 600, pause 250, ... */
    val PATTERN = longArrayOf(0, 600, 250, 600, 250, 600)

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("signals")
        val signals = NotificationChannel(CH_SIGNALS, "Signals and trades", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A new signal, a placed trade, or a problem. Vibrates and makes a sound."
            enableVibration(true)
            vibrationPattern = PATTERN
            enableLights(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
            )
        }
        nm.createNotificationChannel(signals)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Copier running", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Signal copier is running")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    fun signal(context: Context, title: String, text: String) {
        // Extra vibration straight from the app, so it buzzes even if notification vibration is off or the phone is on silent.
        val app = context.applicationContext as? App
        if (app?.prefs?.extraVibrate != false) vibrate(context)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CH_SIGNALS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(PATTERN)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    fun vibrate(context: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(PATTERN, -1)
            }
        } catch (_: Exception) {
        }
    }

    /** Opens Android's own settings for the signal notifications (sound, vibration, pop-up). */
    fun openChannelSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CH_SIGNALS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
