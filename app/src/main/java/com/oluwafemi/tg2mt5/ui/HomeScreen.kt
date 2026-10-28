package com.oluwafemi.tg2mt5.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.CopierService
import com.oluwafemi.tg2mt5.LogEntry
import com.oluwafemi.tg2mt5.TgState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(app: App, modifier: Modifier, onGoTelegram: () -> Unit, onGoSettings: () -> Unit) {
    val context = LocalContext.current
    val tg by app.telegram.state.collectAsStateWithLifecycle()
    val entries by app.log.entries.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(app.prefs.copierEnabled) }
    var batteryOk by remember { mutableStateOf(ignoringBattery(context)) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column {
                Gap(12)
                Text("TG2MT5 Copier", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Hint("Telegram signals → MetaTrader 5")
                Gap()
            }
        }
        item {
            Section("Status") {
                app.nativeLibError?.let { Hint("Telegram library error: $it", BadRed) }
                val tgText = when (val s = tg) {
                    is TgState.Ready -> "Logged in as ${s.name}" to GoodGreen
                    TgState.NoCredentials -> "Not set up" to BadRed
                    else -> "Login not finished" to Gold
                }
                StatusRow("Telegram", tgText.first, tgText.second) { onGoTelegram() }
                val n = app.prefs.channelIds.size
                StatusRow("Chats watched", if (n == 0) "None selected" else "$n", if (n == 0) BadRed else GoodGreen) { onGoTelegram() }
                StatusRow("Mode", if (app.prefs.isAuto) "Auto-trade (MetaApi)" else "Alert only", SkyBlue) { onGoSettings() }
                if (app.prefs.isAuto) {
                    val ok = app.prefs.metaToken.isNotBlank() && app.prefs.metaAccountId.isNotBlank()
                    StatusRow("MetaApi", if (ok) "Configured" else "Token / account missing", if (ok) GoodGreen else BadRed) { onGoSettings() }
                }
                val lot = if (app.prefs.lotMode == "fixed") "Fixed ${app.prefs.fixedLotText}" else "Risk ${app.prefs.riskPercentText}%"
                StatusRow("Lot size", "$lot · ${if (app.prefs.isCent) "Cent" else "Standard"} account", SkyBlue) { onGoSettings() }
            }
        }
        item { Column {
            if (running) {
                Button(
                    onClick = { CopierService.stop(context); app.prefs.copierEnabled = false; running = false; app.log.add("info", "Copier stopped") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BadRed, contentColor = Color.White),
                ) { Text("Stop copier") }
                Hint("Running. Signals from your selected chats are being ${if (app.prefs.isAuto) "traded automatically" else "sent to you as alerts"}.", GoodGreen)
            } else {
                Button(
                    onClick = { CopierService.start(context); app.prefs.copierEnabled = true; running = true; app.log.add("info", "Copier started (${if (app.prefs.isAuto) "auto-trade" else "alert only"})") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start copier") }
                Hint("Stopped. Nothing will be copied until you start it.")
            }
            if (!batteryOk) {
                Gap(4)
                OutlinedButton(onClick = { askBattery(context); batteryOk = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow running in the background")
                }
                Hint("Recommended: stops Android from pausing the copier when the screen is off.")
            }
        } }
        item { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) TextButton(onClick = { app.log.clear() }) { Text("Clear") }
            }
            if (entries.isEmpty()) Hint("Nothing yet. Signals, alerts and trades will appear here.")
        } }
        items(entries) { LogCard(it) }
        item { Gap(16) }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(value, color = color) }
    }
}

private val timeFmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())

@Composable
private fun LogCard(e: LogEntry) {
    val color = when (e.kind) {
        "trade" -> GoodGreen
        "error" -> BadRed
        "signal", "alert" -> Gold
        "outcome" -> SkyBlue
        else -> Muted
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(e.kind.uppercase(), color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(timeFmt.format(Date(e.time)), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(e.title, style = MaterialTheme.typography.bodyMedium)
            if (e.detail.isNotBlank()) Hint(e.detail)
        }
    }
}

private fun ignoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
private fun askBattery(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
