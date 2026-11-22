package com.oluwafemi.tg2mt5.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.Backup
import com.oluwafemi.tg2mt5.BuildConfig
import com.oluwafemi.tg2mt5.Notifier
import com.oluwafemi.tg2mt5.TgState
import kotlinx.coroutines.launch

/** The Setup tab: a short list of setup areas, each opening its own page. */
@Composable
fun SetupScreen(app: App, modifier: Modifier, go: (String?) -> Unit) {
    val p = app.prefs
    val tg by app.telegram.state.collectAsStateWithLifecycle()
    val channels by app.setups.channels.collectAsStateWithLifecycle()
    val accounts by app.setups.accounts.collectAsStateWithLifecycle()
    Page(modifier) {
        ScreenTitle("Setup", "Tap a section to change it.")
        Section("Connections") {
            NavRow("Telegram login", if (tg is TgState.Ready) "Logged in as ${(tg as TgState.Ready).name}" else "Not logged in", tg is TgState.Ready) { go("telegram") }
            NavRow("MetaApi token", if (p.metaToken.isNotBlank()) "Saved · used by ${accounts.count { it.tokenOverride.isBlank() }} account(s)" else "Not saved yet", p.metaToken.isNotBlank()) { go("metaapi") }
        }
        Section("Channels and accounts") {
            NavRow("Add a signal channel", "${channels.size} channel(s) added", null) { go("addchannel") }
            NavRow("Add an MT5 account", "${accounts.size} account(s) added", null) { go("account:new") }
            Hint("Manage and link them on the Overview tab.")
        }
        Section("Alerts") {
            NavRow("Notifications and vibration", if (p.extraVibrate) "Strong vibration on" else "Strong vibration off", null) { go("alerts") }
        }
        Section("Tools") {
            NavRow("Test a signal", "Paste a message to see how a channel's signal is read and what would be placed", null) { go("test") }
            NavRow("Backup and restore", "Save everything to a file, or load it into this or the other version of the app", null) { go("backup") }
        }
        Hint("Version ${BuildConfig.VERSION_NAME} · ${if (app.strictPairs) "Pairs" else "Flexible"} version")
        Gap(16)
    }
}

/** One setup page, with a back link to the Setup list. */
@Composable
fun SetupPage(app: App, modifier: Modifier, page: String, go: (String?) -> Unit, onBack: () -> Unit) {
    when (page) {
        "telegram" -> TelegramScreen(app, modifier, go, onBack)
        "metaapi" -> MetaApiScreen(app, modifier, go, onBack)
        "alerts" -> AlertsScreen(app, modifier, onBack)
        "backup" -> BackupScreen(app, modifier, onBack)
        "test" -> TestScreen(app, modifier, onBack)
        else -> SetupScreen(app, modifier, go)
    }
}

@Composable
fun MetaApiScreen(app: App, modifier: Modifier, go: (String?) -> Unit, onBack: () -> Unit) {
    val p = app.prefs
    val scope = rememberCoroutineScope()
    val accounts by app.setups.accounts.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(p.metaToken) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(p.verifyPrices) }

    Page(modifier) {
        ScreenTitle("MetaApi", "MetaApi connects the app to your MT5 accounts so trades can be placed automatically.", onBack)
        Section("Your MetaApi token") {
            Hint(
                "1. Log in at app.metaapi.cloud.\n" +
                    "2. Open API Access (left menu) and create or copy your API token.\n" +
                    "3. Paste it here and tap Save. You only do this once: every MT5 account in the app uses it.",
            )
            Field("API token", token, { token = it; saved = false }, secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = token.isNotBlank() && token != p.metaToken, onClick = { p.metaToken = token; saved = true }) { Text("Save token") }
                OutlinedButton(enabled = !busy && accounts.any { it.metaAccountId.isNotBlank() }, onClick = {
                    busy = true
                    scope.launch { app.processor.refreshAllStatuses(); busy = false }
                }) { Text(if (busy) "Checking…" else "Check all accounts") }
            }
            if (saved) Hint("Saved.", GoodGreen)
            Hint("Keep the token private: anyone with it can trade your MetaApi accounts.")
        }
        Section("Accounts using MetaApi") {
            if (accounts.isEmpty()) Hint("No MT5 accounts yet.")
            accounts.forEach { a ->
                NavRow(a.name, a.status.level.label + (if (a.metaAccountId.isBlank()) " · no account ID" else ""), a.status.level == com.oluwafemi.tg2mt5.MetaStatus.Level.RUNNING) {
                    go("account:${a.id}")
                }
            }
            TextButton(onClick = { go("account:new") }) { Text("+ Add MT5 account") }
        }
        Section("Price checks") {
            SwitchRow(
                "Check channel signals against real prices",
                "Every 5 minutes, checks whether each channel signal reached TP or SL first, using price data from one of your MetaApi accounts. Catches losses a channel never posts. MetaApi may charge a little for market data.",
                verify,
            ) { verify = it; p.verifyPrices = it }
        }
        Section("Cost") {
            Hint("MetaApi is pay-as-you-go: about \$0.0126 per hour per account on High reliability (cloud-g2), roughly \$9 a month, plus \$2.10 once when you add an account. Prices are set by MetaApi and can change; check Billing › View rates in your MetaApi dashboard. The 'Pay for and connect MetaApi' guide explains it step by step.")
        }
        Gap(16)
    }
}

@Composable
fun AlertsScreen(app: App, modifier: Modifier, onBack: () -> Unit) {
    val p = app.prefs
    val context = LocalContext.current
    var vibrate by remember { mutableStateOf(p.extraVibrate) }
    var notifyResults by remember { mutableStateOf(p.notifyChannelResults) }
    Page(modifier) {
        ScreenTitle("Notifications and vibration", "How the app gets your attention.", onBack)
        Section("") {
            SwitchRow("Strong vibration", "Three strong buzzes for each new signal, trade or result. Works on silent (not in Do Not Disturb).", vibrate) { vibrate = it; p.extraVibrate = it }
            SwitchRow("Alert me when a channel posts a result", "TP hit / SL hit messages. You are always alerted when a channel deletes a loss result or a signal.", notifyResults) { notifyResults = it; p.notifyChannelResults = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Notifier.signal(context, "Test signal", "XAUUSD SELL @ 4054.5  TP 4044.5  SL 4064.5\nThis is only a test.") }) { Text("Send test") }
                OutlinedButton(onClick = { Notifier.openChannelSettings(context) }) { Text("Phone settings") }
            }
            Hint("Turn alerts on or off for each channel with its 'Alert me' switch on the Overview tab.")
        }
        Gap(16)
    }
}

@Composable
fun BackupScreen(app: App, modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(app).toByteArray()) }
            "✔ Backup saved."
        } catch (e: Exception) { "✖ Could not save: ${e.message}" }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }

    Page(modifier) {
        ScreenTitle("Backup and restore", "Saves your channels, MT5 accounts, settings, channel record and activity to one file.", onBack)
        Section("Save a backup") {
            Hint("Includes your MetaApi token and Telegram API ID, so keep the file private. Your Telegram login itself is not included: after restoring, log in to Telegram again.")
            Button(onClick = { exportLauncher.launch("tg2mt5-backup.json") }) { Text("Save backup file") }
        }
        Section("Restore") {
            Hint("Replaces everything in this app with the backup. Use it to move your setup into the other version (Flexible ↔ Pairs) or a new phone.")
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Choose backup file") }
        }
        message?.let { Hint(it, if (it.startsWith("✔")) GoodGreen else BadRed) }
        Gap(16)
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Everything in this app is replaced. The copier is turned off; turn it on again on the Overview after checking your setups.") },
            confirmButton = {
                TextButton(onClick = {
                    message = try {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                        Backup.restore(app, text)
                    } catch (e: Exception) { "✖ Could not restore: ${e.message}" }
                    pendingRestore = null
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}
