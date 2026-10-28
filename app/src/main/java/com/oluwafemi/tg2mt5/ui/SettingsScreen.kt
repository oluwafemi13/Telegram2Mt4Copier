package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oluwafemi.tg2mt5.App
import androidx.compose.ui.platform.LocalContext
import com.oluwafemi.tg2mt5.MetaApiClient
import com.oluwafemi.tg2mt5.Notifier
import com.oluwafemi.tg2mt5.fmt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: App, modifier: Modifier) {
    val p = app.prefs
    val scope = rememberCoroutineScope()

    // Local copies so the screen updates as you type; every change is saved immediately.
    val context = LocalContext.current
    var vibrate by remember { mutableStateOf(p.extraVibrate) }
    var mode by remember { mutableStateOf(p.mode) }
    var token by remember { mutableStateOf(p.metaToken) }
    var account by remember { mutableStateOf(p.metaAccountId) }
    var region by remember { mutableStateOf(p.metaRegion) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var accountType by remember { mutableStateOf(p.accountType) }
    var centFactor by remember { mutableStateOf(p.centFactorText) }
    var lotMode by remember { mutableStateOf(p.lotMode) }
    var fixedLot by remember { mutableStateOf(p.fixedLotText) }
    var risk by remember { mutableStateOf(p.riskPercentText) }
    var maxLot by remember { mutableStateOf(p.maxLotText) }
    var useMin by remember { mutableStateOf(p.useMinLot) }
    var equity by remember { mutableStateOf(p.alertEquityText) }
    var prefix by remember { mutableStateOf(p.symbolPrefix) }
    var suffix by remember { mutableStateOf(p.symbolSuffix) }
    var map by remember { mutableStateOf(p.symbolMap) }
    var orderType by remember { mutableStateOf(p.orderType) }
    var tpChoice by remember { mutableStateOf(p.tpChoice) }
    var maxDist by remember { mutableStateOf(p.maxDistanceText) }
    var maxAge by remember { mutableStateOf(p.maxAgeText) }

    val unit = if (accountType == "cent") "cent lots" else "lots"

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Gap(12)
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Hint("Changes are saved automatically. Restart the copier after changing the mode.")
        Gap()

        Section("Mode") {
            Choice(listOf("alert" to "Alert only", "auto" to "Auto-trade"), mode) { mode = it; p.mode = it }
            Hint(
                if (mode == "auto") "Signals are placed on your MT5 account instantly through MetaApi."
                else "You get a notification for each signal and place the trade yourself. Free; no MetaApi needed.",
            )
        }

        Section("Notifications and vibration") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strong vibration on every signal", Modifier.weight(1f))
                Switch(checked = vibrate, onCheckedChange = { vibrate = it; p.extraVibrate = it })
            }
            Hint("Three strong buzzes for each new signal or trade. Works even when the phone is on silent (not in Do Not Disturb).")
            Row {
                OutlinedButton(onClick = {
                    Notifier.signal(context, "Test signal", "XAUUSD SELL @ 4054.5  TP 4044.5  SL 4064.5\nThis is only a test.")
                }) { Text("Send test") }
                Gap()
                OutlinedButton(onClick = { Notifier.openChannelSettings(context) }) { Text("Phone settings") }
            }
            Hint("Phone settings opens Android's options for these alerts: sound, vibration, pop-up and lock screen.")
        }

        Section("MetaApi (for Auto-trade)") {
            Hint("From app.metaapi.cloud: create an API token, add your MT5 account, and copy its account ID.")
            Field("API token", token, { token = it; p.metaToken = it }, secret = true)
            Field("MetaApi account ID", account, { account = it; p.metaAccountId = it })
            Hint("Region (where your account is deployed in MetaApi)")
            Choice(listOf("new-york" to "New York", "london" to "London", "singapore" to "Singapore"), region) { region = it; p.metaRegion = it }
            OutlinedButton(onClick = {
                testResult = "Connecting…"
                scope.launch {
                    testResult = try {
                        val i = MetaApiClient(p.metaToken, p.metaAccountId, p.metaRegion).accountInfo()
                        "✔ Connected: ${i.broker} #${i.login}\nBalance ${fmt(i.balance)} ${i.currency} · Equity ${fmt(i.equity)} ${i.currency}" +
                            if (!i.tradeAllowed) "\n⚠ Trading is not allowed on this account (investor password?)" else ""
                    } catch (e: Exception) {
                        "✖ ${e.message}"
                    }
                }
            }) { Text("Test connection") }
            testResult?.let { Hint(it, if (it.startsWith("✔")) GoodGreen else if (it.startsWith("✖")) BadRed else Muted) }
        }

        Section("Account type") {
            Choice(listOf("standard" to "Standard", "cent" to "Cent"), accountType) { accountType = it; p.accountType = it }
            if (accountType == "cent") {
                Field("Standard lots in 1 cent lot", centFactor, { centFactor = it; p.centFactorText = it }, number = true)
                Hint("Usually 0.01 (1 cent lot = 0.01 standard lot). Only used for Alert-only estimates; Auto-trade uses your broker's exact values.")
            }
        }

        Section("Lot size") {
            Choice(listOf("fixed" to "Fixed lot", "risk" to "Risk % of equity"), lotMode) { lotMode = it; p.lotMode = it }
            if (lotMode == "fixed") {
                Field("Lot size ($unit)", fixedLot, { fixedLot = it; p.fixedLotText = it }, number = true)
            } else {
                Field("Risk per trade (%)", risk, { risk = it; p.riskPercentText = it }, number = true)
                Hint("Lot = (equity × risk %) ÷ loss per lot at the signal's SL. Your signals suggest 1–2%.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("If below broker minimum, use minimum lot", Modifier.weight(1f))
                    Switch(checked = useMin, onCheckedChange = { useMin = it; p.useMinLot = it })
                }
                Hint(if (useMin) "Small accounts will still trade, but may risk more than your %." else "Trades that would need less than the minimum lot are skipped.")
                Field("Your equity (for Alert-only estimates)", equity, { equity = it; p.alertEquityText = it }, number = true)
                Hint(if (accountType == "cent") "Enter it as shown in MT5 (in USC)." else "Enter it as shown in MT5.")
            }
            Field("Maximum lot (0 = no limit)", maxLot, { maxLot = it; p.maxLotText = it }, number = true)
        }

        Section("Symbol names at your broker") {
            Hint("Many brokers add letters to symbols, e.g. XAUUSDm or XAUUSDc. Check the exact name in MT5's Market Watch.")
            Field("Prefix (usually empty)", prefix, { prefix = it; p.symbolPrefix = it })
            Field("Suffix (e.g. m, c, .pro)", suffix, { suffix = it; p.symbolSuffix = it })
            Hint("Example: XAUUSD → ${p.brokerSymbol("XAUUSD")}")
            Field("Special names, one per line (e.g. XAUUSD=GOLD)", map, { map = it; p.symbolMap = it }, singleLine = false, minLines = 2)
        }

        Section("Orders") {
            Hint("Order type")
            Choice(listOf("market" to "Market (instant)", "pending" to "Pending at signal price"), orderType) { orderType = it; p.orderType = it }
            Hint("Which TP to use when a signal has several")
            Choice(listOf("first" to "First TP", "last" to "Last TP"), tpChoice) { tpChoice = it; p.tpChoice = it }
            Field("Skip if price is this far from entry (0 = never)", maxDist, { maxDist = it; p.maxDistanceText = it }, number = true)
            Hint("In price units. For gold, 3 means skip if price moved more than \$3 from the signal's entry.")
            Field("Ignore signals older than (minutes)", maxAge, { maxAge = it; p.maxAgeText = it }, number = true)
            Hint("Protects you from old signals being traded after your phone was offline.")
        }
        Gap(24)
    }
}
