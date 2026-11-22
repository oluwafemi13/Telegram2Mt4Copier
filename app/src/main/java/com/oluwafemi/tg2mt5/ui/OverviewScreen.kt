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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.oluwafemi.tg2mt5.Channel
import com.oluwafemi.tg2mt5.CopierService
import com.oluwafemi.tg2mt5.Ledger
import com.oluwafemi.tg2mt5.Mt5Account
import com.oluwafemi.tg2mt5.TgState
import com.oluwafemi.tg2mt5.fmt

@Composable
fun OverviewScreen(app: App, modifier: Modifier, go: (String?) -> Unit) {
    val context = LocalContext.current
    val p = app.prefs
    val tg by app.telegram.state.collectAsStateWithLifecycle()
    val channels by app.setups.channels.collectAsStateWithLifecycle()
    val accounts by app.setups.accounts.collectAsStateWithLifecycle()
    val signals by app.ledger.signals.collectAsStateWithLifecycle()
    val loose by app.ledger.loose.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(p.copierEnabled) }
    var batteryOk by remember { mutableStateOf(ignoringBattery(context)) }

    // Refresh MetaApi status when the Overview opens (at most every 2 minutes).
    LaunchedEffect(Unit) {
        val stale = accounts.any { it.metaAccountId.isNotBlank() && System.currentTimeMillis() - it.status.checkedAt > 120_000 }
        if (stale) try { app.processor.refreshAllStatuses() } catch (_: Exception) {}
    }

    val tgReady = tg is TgState.Ready
    val needsToken = accounts.any { it.tokenOverride.isBlank() } && p.metaToken.isBlank()
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val anyLinked = accounts.any { it.channelId != null }
    val allSet = tgReady && channels.isNotEmpty() && !needsToken && notifOk && batteryOk

    Page(modifier) {
        ScreenTitle(
            "Overview",
            if (app.strictPairs) "Pairs version: each channel is paired with exactly one MT5 account"
            else "Flexible version: one channel can feed several MT5 accounts",
        )

        // 1. Master switch
        Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = if (running) Color(0xFF123B2C) else MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val copying = accounts.count { it.copyOn && it.channelId != null }
                val alerting = channels.count { it.alerts }
                Text(if (running) "Copier is ON" else "Copier is OFF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = if (running) GoodGreen else MaterialTheme.colorScheme.onSurface)
                Text(
                    if (running) "Alerts on for $alerting channel(s). Copying trades to $copying MT5 account(s)."
                    else "Nothing is alerted or copied. Signals are still recorded on the Results tab.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        if (running) {
                            CopierService.stop(context); p.copierEnabled = false; running = false; app.log.add("info", "Copier turned off")
                        } else {
                            p.copierEnabled = true; CopierService.start(context); running = true; app.log.add("info", "Copier turned on")
                        }
                    },
                    enabled = running || (tgReady && channels.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = if (running) ButtonDefaults.buttonColors(containerColor = BadRed, contentColor = Color.White) else ButtonDefaults.buttonColors(),
                ) { Text(if (running) "Turn OFF" else "Turn ON", style = MaterialTheme.typography.titleMedium) }
            }
        }

        // 2. How the switches work, in one line each
        Section("How it works") {
            Text("• Alert me (per channel): your phone vibrates with each new signal and a suggested lot.", style = MaterialTheme.typography.bodyMedium)
            Text("• Copy trades (per MT5 account): the signal is placed on that account automatically.", style = MaterialTheme.typography.bodyMedium)
            Text("• Use one, both, or neither. Turn ON above to start.", style = MaterialTheme.typography.bodyMedium)
        }

        // 3. Checklist
        if (!allSet) {
            Section("Finish setting up") {
                NavRow("Log in to Telegram", if (tgReady) "Logged in" else "Needed to read your signal channels", tgReady) { go("telegram") }
                NavRow("Add a signal channel", if (channels.isNotEmpty()) "${channels.size} added" else "Choose the channel that posts signals", channels.isNotEmpty()) { go("addchannel") }
                NavRow("Save your MetaApi token", if (!needsToken) "Saved" else "Needed to copy trades to MT5", !needsToken) { go("metaapi") }
                NavRow("Allow notifications", if (notifOk) "Allowed" else "Needed for alerts and vibration", notifOk) { openNotificationSettings(context) }
                NavRow("Run in the background", if (batteryOk) "Allowed" else "Stops Android pausing the copier", batteryOk) { askBattery(context); batteryOk = true }
            }
        }

        // 4. Setups, grouped by channel
        Gap(4)
        Text("Your copy setups", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Hint(if (app.strictPairs) "Each channel can copy to one MT5 account." else "Each MT5 account copies one channel. A channel can copy to several accounts.")
        Gap(4)
        if (channels.isEmpty()) {
            Section("No channels yet") {
                Hint("Add the Telegram channel that posts your signals, then link an MT5 account to it.")
                Button(onClick = { go("addchannel") }) { Text("Add a channel") }
            }
        }
        AdaptiveGrid(channels) { ch ->
            ChannelCard(app, ch, accounts.filter { it.channelId == ch.chatId }, go) {
                val st = Ledger.stats(signals, loose, System.currentTimeMillis() / 1000 - 7 * 86400, ch.chatId)
                val flags = st.deletedLossResults + st.deletedSignals + st.silentLosses + st.mismatches
                Hint("Last 7 days: ${st.signals} signals · win rate shown ${st.visibleRate?.let { "$it%" } ?: "–"} · posted ${st.claimedRate?.let { "$it%" } ?: "–"}")
                if (flags > 0) Pill("$flags red flag${if (flags == 1) "" else "s"} · see Results", BadRed)
            }
        }

        val unlinked = accounts.filter { it.channelId == null || channels.none { c -> c.chatId == it.channelId } }
        if (unlinked.isNotEmpty()) {
            Section("MT5 accounts not linked to a channel") {
                unlinked.forEach { AccountRow(app, it, go) }
            }
        }

        Gap(4)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { go("addchannel") }, modifier = Modifier.weight(1f)) { Text("+ Add channel") }
            OutlinedButton(onClick = { go("account:new") }, modifier = Modifier.weight(1f)) { Text("+ Add MT5 account") }
        }
        if (!anyLinked && accounts.isNotEmpty()) Hint("Tip: open an MT5 account and choose which channel it copies.", Gold)
        Gap(20)
    }
}

@Composable
private fun ChannelCard(app: App, ch: Channel, linked: List<Mt5Account>, go: (String?) -> Unit, stats: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ch.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Telegram channel", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                TextButton(onClick = { go("channel:${ch.chatId}") }) { Text("Edit ›") }
            }
            stats()
            SwitchRow("Alert me", "Vibrate and notify me for every new signal", ch.alerts) { app.setups.saveChannel(ch.copy(alerts = it)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text("Copies trades to", style = MaterialTheme.typography.labelLarge, color = Muted)
            if (linked.isEmpty()) Hint("No MT5 account linked yet.")
            linked.forEach { AccountRow(app, it, go) }
            if (linked.isEmpty() || !app.strictPairs) {
                TextButton(onClick = { go("account:new:${ch.chatId}") }) {
                    Text(if (linked.isEmpty()) "+ Link an MT5 account" else "+ Link another MT5 account")
                }
            }
        }
    }
}

@Composable
private fun AccountRow(app: App, a: Mt5Account, go: (String?) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { go("account:${a.id}") }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    HGap(); Pill(if (a.isCent) "Cent" else "Standard", SkyBlue)
                }
                StatusBadge(a.status)
                val money = a.status.equity?.let { "Equity ${fmt(Math.round(it * 100) / 100.0)} ${a.status.currency}" } ?: ""
                val lot = if (a.lotMode == "fixed") "Lot ${fmt(a.fixedLot)}" else "Risk ${fmt(a.riskPercent)}%"
                Hint(listOf(money, lot, "Edit ›").filter { it.isNotBlank() }.joinToString(" · "))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = a.copyOn,
                    onCheckedChange = { on ->
                        if (on && a.metaAccountId.isBlank()) go("account:${a.id}")
                        else app.setups.saveAccount(a.copy(copyOn = on))
                    },
                    enabled = a.channelId != null,
                )
                Text(if (a.copyOn) "Copying" else "Copy off", style = MaterialTheme.typography.labelSmall, color = if (a.copyOn) GoodGreen else Muted)
            }
        }
    }
}

fun ignoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun openNotificationSettings(context: Context) {
    val i = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

@SuppressLint("BatteryLife")
fun askBattery(context: Context) {
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
