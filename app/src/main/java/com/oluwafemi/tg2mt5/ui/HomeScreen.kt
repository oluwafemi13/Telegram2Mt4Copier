package com.oluwafemi.tg2mt5.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.CopierService
import com.oluwafemi.tg2mt5.Ledger
import com.oluwafemi.tg2mt5.Nav
import com.oluwafemi.tg2mt5.TgState
import java.util.Calendar

@Composable
fun HomeScreen(app: App, modifier: Modifier, open: (String, String?) -> Unit) {
    val context = LocalContext.current
    val p = app.prefs
    val tg by app.telegram.state.collectAsStateWithLifecycle()
    val entries by app.log.entries.collectAsStateWithLifecycle()
    val signals by app.ledger.signals.collectAsStateWithLifecycle()
    val loose by app.ledger.loose.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(p.copierEnabled) }
    var batteryOk by remember { mutableStateOf(ignoringBattery(context)) }

    val tgReady = tg is TgState.Ready
    val hasChannel = p.channelIds.isNotEmpty()
    val modeReady = !p.isAuto || (p.metaToken.isNotBlank() && p.metaAccountId.isNotBlank())
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val allSet = tgReady && hasChannel && modeReady && notifOk && batteryOk

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("TG2MT5 Copier", "Telegram signals to MetaTrader 5")
        app.nativeLibError?.let { Hint("Telegram library error: $it", BadRed) }

        // 1. The big on/off card
        Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = if (running) Color(0xFF123B2C) else MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (running) "Copier is ON" else "Copier is OFF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = if (running) GoodGreen else MaterialTheme.colorScheme.onSurface)
                        Text(
                            when {
                                !running -> "Nothing is being copied."
                                p.isAuto -> "New signals are traded automatically on MT5."
                                else -> "You get an alert for each new signal and trade it yourself."
                            },
                            color = Muted, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Pill(if (p.isAuto) "Auto-trade" else "Alert only", if (p.isAuto) GoodGreen else SkyBlue)
                }
                Button(
                    onClick = {
                        if (running) {
                            CopierService.stop(context); p.copierEnabled = false; running = false; app.log.add("info", "Copier stopped")
                        } else {
                            CopierService.start(context); p.copierEnabled = true; running = true
                            app.log.add("info", "Copier started (${if (p.isAuto) "auto-trade" else "alert only"})")
                        }
                    },
                    enabled = running || (tgReady && hasChannel),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = if (running) ButtonDefaults.buttonColors(containerColor = BadRed, contentColor = Color.White) else ButtonDefaults.buttonColors(),
                ) { Text(if (running) "Turn OFF" else "Turn ON", style = MaterialTheme.typography.titleMedium) }
                if (!running && !(tgReady && hasChannel)) Hint("Finish the setup steps below first.", Gold)
            }
        }

        // 2. Setup checklist, until everything is done
        if (!allSet) {
            Section("Finish setting up") {
                NavRow("Connect Telegram", if (tgReady) "Logged in" else "Log in with your phone number", tgReady) { open(Nav.SETUP, "telegram") }
                NavRow("Choose your signal channel", if (hasChannel) "${p.channelIds.size} chosen" else "Pick the channel that posts signals", hasChannel) { open(Nav.SETUP, "telegram") }
                NavRow("Trading mode", if (p.isAuto) (if (modeReady) "Auto-trade, MetaApi connected" else "Auto-trade needs your MetaApi token") else "Alert only (free)", modeReady) { open(Nav.SETUP, "mode") }
                NavRow("Allow notifications", if (notifOk) "Allowed" else "Needed for alerts and vibration", notifOk) { openAppSettings(context) }
                NavRow("Run in the background", if (batteryOk) "Allowed" else "Stops Android pausing the copier", batteryOk) { askBattery(context); batteryOk = true }
            }
        }

        // 3. Today at a glance
        val today = startOfToday()
        val todayEntries = entries.filter { it.time / 1000 >= today }
        val ch = Ledger.stats(signals, loose, today, null)
        Section("Today") {
            Text("My trades", style = MaterialTheme.typography.labelLarge, color = Muted)
            Row(Modifier.fillMaxWidth()) {
                StatTile("${todayEntries.count { it.kind == "win" }}", "Wins", GoodGreen, Modifier.weight(1f))
                StatTile("${todayEntries.count { it.kind == "loss" }}", "Losses", BadRed, Modifier.weight(1f))
                StatTile("${todayEntries.count { it.kind == "failed" }}", "Failed", BadRed, Modifier.weight(1f))
                StatTile("${todayEntries.count { it.kind == "skipped" }}", "Skipped", Gold, Modifier.weight(1f))
            }
            Text("Channel", style = MaterialTheme.typography.labelLarge, color = Muted)
            Row(Modifier.fillMaxWidth()) {
                StatTile("${ch.signals}", "Signals", SkyBlue, Modifier.weight(1f))
                StatTile("${ch.claimedWins}", "TP posted", GoodGreen, Modifier.weight(1f))
                StatTile("${ch.claimedLosses}", "SL posted", BadRed, Modifier.weight(1f))
                StatTile("${ch.deletedLossResults + ch.deletedSignals}", "Deleted", if (ch.deletedLossResults + ch.deletedSignals > 0) BadRed else Muted, Modifier.weight(1f))
            }
            TextButton(onClick = { open(Nav.CHANNEL, null) }) { Text("Open channel dashboard ›") }
        }

        // 4. Latest activity
        Section("Latest activity") {
            if (entries.isEmpty()) Hint("Nothing yet. New signals, alerts and trades will appear here.")
            entries.take(4).forEach { ActivityRow(it) }
            if (entries.size > 4) TextButton(onClick = { open(Nav.ACTIVITY, null) }) { Text("See all activity ›") }
        }
        Gap(16)
    }
}

private fun startOfToday(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}

private fun ignoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAppSettings(context: Context) {
    val i = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

@SuppressLint("BatteryLife")
private fun askBattery(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
