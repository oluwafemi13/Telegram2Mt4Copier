package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.ParseResult
import com.oluwafemi.tg2mt5.SignalParser
import com.oluwafemi.tg2mt5.SignalWords

@Composable
fun ChannelEditScreen(app: App, modifier: Modifier, chatId: Long, go: (String?) -> Unit, onBack: () -> Unit) {
    val channels by app.setups.channels.collectAsStateWithLifecycle()
    val accounts by app.setups.accounts.collectAsStateWithLifecycle()
    val channel = channels.firstOrNull { it.chatId == chatId }
    if (channel == null) {
        Page(modifier) {
            ScreenTitle("Channel removed", "", onBack)
        }
        return
    }
    val linked = accounts.filter { it.channelId == chatId }
    var w by remember(chatId) { mutableStateOf(channel.words) }
    var sample by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = w != channel.words

    Page(modifier) {
        ScreenTitle(channel.title, "Telegram channel", onBack)

        Section("Alerts") {
            SwitchRow("Alert me", "Vibrate and notify me for every new signal from this channel, with a suggested lot.", channel.alerts) {
                app.setups.saveChannel(channel.copy(alerts = it))
            }
        }

        Section("Copies trades to") {
            if (linked.isEmpty()) Hint("No MT5 account copies this channel yet.")
            linked.forEach { a ->
                NavRow(a.name, (if (a.copyOn) "Copying · " else "Copy off · ") + a.status.level.label, a.copyOn) { go("account:${a.id}") }
            }
            if (linked.isEmpty() || !app.strictPairs) {
                TextButton(onClick = { go("account:new:$chatId") }) { Text(if (linked.isEmpty()) "+ Link an MT5 account" else "+ Link another MT5 account") }
            } else {
                Hint("Pairs version: this channel is already paired with ${linked.first().name}.")
            }
        }

        Section("Signal wording") {
            Hint(
                "Tell the app how this channel writes its signals. Put every word or phrase the channel uses, separated by commas. " +
                    "Capital letters don't matter. Example: if the channel writes \"Stoploss\" or \"S.L\", put: stoploss, s.l",
            )
            WordField("Buy (market)", w.buy) { w = w.copy(buy = it) }
            WordField("Sell (market)", w.sell) { w = w.copy(sell = it) }
            WordField("Buy limit", w.buyLimit) { w = w.copy(buyLimit = it) }
            WordField("Sell limit", w.sellLimit) { w = w.copy(sellLimit = it) }
            WordField("Buy stop", w.buyStop) { w = w.copy(buyStop = it) }
            WordField("Sell stop", w.sellStop) { w = w.copy(sellStop = it) }
            WordField("Market execution / enter now", w.market) { w = w.copy(market = it) }
            WordField("Entry price", w.entry) { w = w.copy(entry = it) }
            WordField("Stop loss", w.stopLoss) { w = w.copy(stopLoss = it) }
            WordField("Take profit", w.takeProfit) { w = w.copy(takeProfit = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = dirty, onClick = { app.setups.saveChannel(channel.copy(words = w)); saved = true }) { Text("Save wording") }
                OutlinedButton(onClick = { w = SignalWords.DEFAULT; saved = false }) { Text("Reset to standard") }
            }
            if (saved && !dirty) Hint("Saved. New signals from this channel will be read with these words.", GoodGreen)
            if (dirty) Hint("Not saved yet.", Gold)
        }

        Section("Test with a real message") {
            Hint("Paste a signal from this channel to check it is read correctly with the wording above (saved or not).")
            Field("Signal message", sample, { sample = it }, singleLine = false, minLines = 4)
            if (sample.isNotBlank()) {
                when (val r = SignalParser.parse(sample, w)) {
                    is ParseResult.Trade -> {
                        val s = r.signal
                        Hint("✔ Read as: ${s.summary()}", GoodGreen)
                        Hint(
                            "Order type: " + when (s.kind) {
                                com.oluwafemi.tg2mt5.OrderKind.LIMIT -> "${s.orderName} (pending order at ${s.entry})"
                                com.oluwafemi.tg2mt5.OrderKind.STOP -> "${s.orderName} (pending order at ${s.entry})"
                                com.oluwafemi.tg2mt5.OrderKind.MARKET -> "${s.direction} at market"
                                com.oluwafemi.tg2mt5.OrderKind.AUTO -> "${s.direction} at market (a price was given but no limit/stop word)"
                            },
                        )
                    }
                    is ParseResult.Outcome -> Hint("ℹ Read as a result message (${when (r.win) { true -> "TP hit"; false -> "SL hit"; null -> "win/loss unclear" }}).", SkyBlue)
                    is ParseResult.Invalid -> Hint("✖ ${r.reason}. Check the wording above.", BadRed)
                    ParseResult.NotASignal -> Hint("✖ Not recognised as a signal. Add the words this channel uses for buy/sell, SL and TP above.", BadRed)
                }
            }
        }

        Gap(4)
        TextButton(onClick = { confirmDelete = true }) { Text("Remove this channel", color = BadRed) }
        Gap(24)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${channel.title}?") },
            text = { Text("The app stops reading this channel. Linked MT5 accounts are kept but unlinked, and copying stops. Its recorded results stay on the Results tab.") },
            confirmButton = {
                TextButton(onClick = { app.setups.deleteChannel(chatId); confirmDelete = false; onBack() }) { Text("Remove", color = BadRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WordField(label: String, value: String, onChange: (String) -> Unit) {
    Field(label, value, onChange)
}
