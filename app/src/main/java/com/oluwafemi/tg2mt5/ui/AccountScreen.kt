package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.LinkRules
import com.oluwafemi.tg2mt5.MetaStatus
import com.oluwafemi.tg2mt5.Mt5Account
import com.oluwafemi.tg2mt5.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Add a new MT5 account (accountId = null) or edit an existing one. linkTo pre-selects a channel. */
@Composable
fun AccountScreen(app: App, modifier: Modifier, accountId: String?, linkTo: Long?, go: (String?) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val channels by app.setups.channels.collectAsStateWithLifecycle()
    val accounts by app.setups.accounts.collectAsStateWithLifecycle()
    val original = remember(accountId) { accountId?.let { app.setups.account(it) } ?: Mt5Account(channelId = linkTo) }
    val isNew = accountId == null

    var name by remember { mutableStateOf(if (isNew) "" else original.name) }
    var metaId by remember { mutableStateOf(original.metaAccountId) }
    var tokenOverride by remember { mutableStateOf(original.tokenOverride) }
    var showToken by remember { mutableStateOf(original.tokenOverride.isNotBlank()) }
    var channelId by remember { mutableStateOf(original.channelId) }
    var copyOn by remember { mutableStateOf(original.copyOn) }
    var type by remember { mutableStateOf(original.accountType) }
    var centFactor by remember { mutableStateOf(fmt(original.centFactor)) }
    var lotMode by remember { mutableStateOf(original.lotMode) }
    var fixedLot by remember { mutableStateOf(fmt(original.fixedLot)) }
    var risk by remember { mutableStateOf(fmt(original.riskPercent)) }
    var maxLot by remember { mutableStateOf(fmt(original.maxLot)) }
    var useMin by remember { mutableStateOf(original.useMinLot) }
    var prefix by remember { mutableStateOf(original.symbolPrefix) }
    var suffix by remember { mutableStateOf(original.symbolSuffix) }
    var symMap by remember { mutableStateOf(original.symbolMap) }
    var orderRule by remember { mutableStateOf(original.orderRule) }
    var tpChoice by remember { mutableStateOf(original.tpChoice) }
    var maxDist by remember { mutableStateOf(fmt(original.maxDistancePips)) }
    var maxAge by remember { mutableStateOf(fmt(original.maxAgeMinutes)) }
    var status by remember { mutableStateOf(original.status) }
    var region by remember { mutableStateOf(original.region) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun num(s: String, def: Double) = s.replace(',', '.').trim().toDoubleOrNull() ?: def
    fun current() = original.copy(
        name = name.trim().ifBlank { "MT5 account" }, metaAccountId = metaId.trim(), tokenOverride = tokenOverride.trim(),
        region = region, channelId = channelId, copyOn = copyOn && channelId != null, accountType = type,
        centFactor = num(centFactor, 0.01).takeIf { it > 0 } ?: 0.01, lotMode = lotMode, fixedLot = num(fixedLot, 0.01),
        riskPercent = num(risk, 1.0), maxLot = num(maxLot, 0.0), useMinLot = useMin, symbolPrefix = prefix.trim(),
        symbolSuffix = suffix.trim(), symbolMap = symMap, orderRule = orderRule, tpChoice = tpChoice,
        maxDistancePips = num(maxDist, 0.0), maxAgeMinutes = num(maxAge, 3.0), status = status,
    )
    fun check() {
        busy = true
        scope.launch {
            val st = app.processor.refreshStatus(current())
            status = st
            if (st.region.isNotBlank()) region = st.region
            busy = false
        }
    }

    val unit = if (type == "cent") "cent lots" else "lots"
    Page(modifier) {
        ScreenTitle(if (isNew) "Add MT5 account" else name.ifBlank { "MT5 account" },
            if (isNew) "Fill in the steps, then tap Save." else "Changes apply when you tap Save.", onBack)

        // Copy switch at the top so it's obvious
        Section("Copy trades") {
            SwitchRow(
                "Copy trades to this account",
                when {
                    channelId == null -> "Choose a channel in step 3 first."
                    copyOn -> "Signals from ${channels.firstOrNull { it.chatId == channelId }?.title ?: "the channel"} will be placed on this account."
                    else -> "Off: signals are not placed on this account."
                },
                copyOn, enabled = channelId != null,
            ) { copyOn = it }
        }

        Section("") {
            StepTitle(1, "Name and account type")
            Field("Name (e.g. Exness Cent)", name, { name = it })
            RadioList(
                listOf(
                    Triple("standard", "Standard account", "Balance in USD; 1 lot = 100,000 units (100 oz for gold)"),
                    Triple("cent", "Cent account", "Balance in US cents (USC); 1 cent lot = 0.01 standard lot at most brokers"),
                ),
                type, { type = it },
            )
            if (type == "cent") {
                Field("Standard lots in 1 cent lot", centFactor, { centFactor = it }, number = true)
                Hint("Usually 0.01. Only used for alert estimates; copying uses your broker's exact values.")
            }
        }

        Section("") {
            StepTitle(2, "Link MetaApi")
            Hint("In app.metaapi.cloud, open your MT5 account and copy its ID (a long code like 1eda642a-a9a3-…).")
            Field("MetaApi account ID", metaId, { metaId = it })
            if (app.prefs.metaToken.isBlank() && tokenOverride.isBlank()) {
                Hint("You haven't saved your MetaApi token yet.", Gold)
                TextButton(onClick = { go("metaapi") }) { Text("Save MetaApi token ›") }
            } else if (!showToken) {
                Hint("Uses the MetaApi token saved in Setup.")
                TextButton(onClick = { showToken = true }) { Text("Use a different token for this account") }
            }
            if (showToken) Field("Token for this account only (leave empty to use the saved one)", tokenOverride, { tokenOverride = it }, secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { check() }, enabled = !busy && metaId.isNotBlank()) { Text(if (busy) "Checking…" else "Check link") }
                if (status.level == MetaStatus.Level.PAUSED || status.state == "UNDEPLOYED") {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { app.processor.client(current()).deploy() } catch (e: Exception) { error = e.message }
                            delay(4000); check()
                        }
                    }) { Text("Start (deploy)") }
                }
                if (status.state == "DEPLOYED") {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { app.processor.client(current()).undeploy() } catch (e: Exception) { error = e.message }
                            delay(3000); check()
                        }
                    }) { Text("Pause (undeploy)") }
                }
            }
            if (status.checkedAt > 0) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        StatusBadge(status)
                        if (status.error.isNotBlank()) Hint(status.error, BadRed)
                        val lines = listOfNotNull(
                            status.broker.ifBlank { null }?.let { "Broker: $it" },
                            status.login.ifBlank { null }?.let { "Login: $it${if (status.server.isNotBlank()) " · ${status.server}" else ""}" },
                            status.equity?.let { "Balance ${fmt(status.balance ?: 0.0)} · Equity ${fmt(it)} ${status.currency}" },
                            region.ifBlank { null }?.let { "Region: $it (set automatically)" },
                        )
                        lines.forEach { Hint(it) }
                        when (status.level) {
                            MetaStatus.Level.PAUSED -> Hint("The account is undeployed, so it can't trade. Tap Start (deploy). MetaApi bills hosting while deployed.", Gold)
                            MetaStatus.Level.STARTING -> Hint("MetaApi is connecting to your broker. Check again in a minute.", Gold)
                            MetaStatus.Level.BROKER -> Hint("MetaApi can't log in to your broker. Check the MT5 password and server in MetaApi.", Gold)
                            else -> {}
                        }
                    }
                }
            }
        }

        Section("") {
            StepTitle(3, "Channel to copy")
            if (channels.isEmpty()) {
                Hint("You haven't added any channels yet.")
                TextButton(onClick = { go("addchannel") }) { Text("+ Add a channel") }
            } else {
                val disabled = channels.mapNotNull { c ->
                    LinkRules.canLink(accounts, original.id, c.chatId, app.strictPairs)?.let { c.chatId.toString() to it }
                }.toMap()
                RadioList(
                    listOf(Triple("none", "Not linked", "This account copies nothing")) +
                        channels.map { c ->
                            val others = accounts.count { it.channelId == c.chatId && it.id != original.id }
                            Triple(c.chatId.toString(), c.title, if (others > 0 && !app.strictPairs) "Also copied by $others other account(s)" else "")
                        },
                    channelId?.toString() ?: "none",
                    { v -> channelId = v.toLongOrNull(); if (channelId == null) copyOn = false },
                    disabled,
                )
                Hint(if (app.strictPairs) "Pairs version: a channel already paired with another account can't be chosen." else "Each account copies one channel. Several accounts can copy the same channel.")
            }
        }

        Section("") {
            StepTitle(4, "Lot size")
            RadioList(
                listOf(
                    Triple("fixed", "Fixed lot", "The same size for every trade"),
                    Triple("risk", "Risk % of equity", "Size worked out from the distance to the signal's SL"),
                ),
                lotMode, { lotMode = it },
            )
            if (lotMode == "fixed") Field("Lot size ($unit)", fixedLot, { fixedLot = it }, number = true)
            else {
                Field("Risk per trade (%)", risk, { risk = it }, number = true)
                SwitchRow("Use the broker minimum if the lot is too small", if (useMin) "Small accounts still trade but may risk more than your %." else "Trades needing less than the minimum are skipped.", useMin) { useMin = it }
            }
            Field("Maximum lot (0 = no limit)", maxLot, { maxLot = it }, number = true)
        }

        Section("") {
            StepTitle(5, "Orders")
            Text("When a signal says BUY LIMIT, SELL STOP etc.", style = MaterialTheme.typography.bodyMedium)
            RadioList(
                listOf(
                    Triple("follow", "Follow the signal (recommended)", "Limit and stop signals become pending orders at the entry price; others are market orders"),
                    Triple("market", "Always market order", "Enter immediately, whatever the signal says"),
                ),
                orderRule, { orderRule = it },
            )
            Text("Which TP to use when a signal has several", style = MaterialTheme.typography.bodyMedium)
            Choice(listOf("first" to "First TP", "last" to "Last TP"), tpChoice) { tpChoice = it }
            Field("Skip if price moved more than (pips, 0 = never)", maxDist, { maxDist = it }, number = true)
            Hint("Market-standard pips: gold 1 pip = \$0.10 (40 pips = \$4, e.g. 3998 → 4002), EURUSD 1 pip = 0.0001, USDJPY 1 pip = 0.01.")
            Field("Ignore signals older than (minutes)", maxAge, { maxAge = it }, number = true)
        }

        Section("") {
            StepTitle(6, "Symbol names at this broker")
            Hint("Check the exact name in MT5 Market Watch, e.g. XAUUSDm or XAUUSDc.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Field("Prefix", prefix, { prefix = it }) }
                Column(Modifier.weight(1f)) { Field("Suffix (e.g. m, c)", suffix, { suffix = it }) }
            }
            Hint("Example: XAUUSD → ${prefix.trim()}XAUUSD${suffix.trim()}")
            Field("Special names, one per line (e.g. XAUUSD=GOLD)", symMap, { symMap = it }, singleLine = false, minLines = 2)
        }

        error?.let { Hint(it, BadRed) }
        Gap(4)
        Button(
            onClick = save@{
                if (name.isBlank()) { error = "Give the account a name in step 1."; return@save }
                val acc = current()
                channelId?.let { ch -> LinkRules.canLink(accounts, acc.id, ch, app.strictPairs)?.let { error = it; return@save } }
                app.setups.saveAccount(acc)
                app.log.add("info", "${if (isNew) "Added" else "Updated"} MT5 account ${acc.name}", acc.channelId?.let { id -> "Copies ${channels.firstOrNull { it.chatId == id }?.title ?: id}" } ?: "Not linked to a channel")
                onBack()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
        if (!isNew) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete this account", color = BadRed) }
        }
        Gap(24)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${original.name}?") },
            text = { Text("It is removed from this app only. Your MT5 and MetaApi accounts are not touched.") },
            confirmButton = {
                TextButton(onClick = { app.setups.deleteAccount(original.id); confirmDelete = false; onBack() }) { Text("Delete", color = BadRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
