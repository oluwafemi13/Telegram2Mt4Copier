package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.ParseResult
import com.oluwafemi.tg2mt5.SignalParser
import com.oluwafemi.tg2mt5.TradePlan
import kotlinx.coroutines.launch

private const val EXAMPLE = "XAUUSD 📉\n\nSell: 4054.5\n\nTP: 4044.5\n\nSL: 4064.5\n\nRisk 1-2% of total Equity"

@Composable
fun TestScreen(app: App, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(EXAMPLE) }
    var parsed by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<TradePlan?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Gap(12)
        Text("Test a signal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Hint("Paste a message from your channel to check how the app reads it, before trusting it with real trades.")
        Gap()
        Field("Signal message", text, { text = it; parsed = null; preview = null; plan = null }, singleLine = false, minLines = 6)
        Gap()
        Button(onClick = {
            preview = null; plan = null
            parsed = when (val r = SignalParser.parse(text)) {
                is ParseResult.Trade -> "✔ ${r.signal.summary()}\n${app.processor.suggestedLotText(r.signal)}"
                is ParseResult.Outcome -> "ℹ This looks like a TP/SL result message. It is logged only, no trade."
                is ParseResult.Invalid -> "✖ ${r.reason}"
                ParseResult.NotASignal -> "✖ Not recognised as a signal (needs BUY/SELL, TP and SL)."
            }
        }) { Text("Check signal") }
        parsed?.let { Gap(4); Hint(it, if (it.startsWith("✔")) GoodGreen else if (it.startsWith("✖")) BadRed else SkyBlue) }

        val trade = (SignalParser.parse(text) as? ParseResult.Trade)?.signal
        if (parsed?.startsWith("✔") == true && trade != null) {
            Gap(16)
            Section("Auto-trade preview") {
                Hint("Uses your MetaApi settings to work out the exact order. Nothing is placed unless you tap Place trade.")
                Row {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true; preview = "Asking MetaApi…"; plan = null
                        scope.launch {
                            try {
                                val pl = app.processor.plan(trade)
                                plan = pl
                                preview = "✔ ${pl.describe()}\n${pl.lotNote}"
                            } catch (e: Exception) {
                                preview = "✖ ${e.message}"
                            }
                            busy = false
                        }
                    }) { Text("Preview order") }
                    Gap()
                }
                preview?.let { Hint(it, if (it.startsWith("✔")) GoodGreen else if (it.startsWith("✖")) BadRed else Muted) }
                if (plan != null) {
                    Button(enabled = !busy, onClick = { confirm = true }) { Text("Place trade") }
                    Hint("Places a REAL order on your account. Try it on a demo account first.", Gold)
                }
            }
        }
        Gap(24)
    }

    val pl = plan
    if (confirm && pl != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Place this trade?") },
            text = { Text(pl.describe()) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; busy = true
                    scope.launch {
                        preview = try {
                            val r = app.processor.execute(pl)
                            app.log.add(if (r.ok) "trade" else "error", "Test trade: ${pl.describe()}", "${r.code} ${r.message}")
                            if (r.ok) "✔ Placed: ${r.code}" else "✖ Rejected: ${r.code} ${r.message}"
                        } catch (e: Exception) {
                            "✖ ${e.message}"
                        }
                        plan = null; busy = false
                    }
                }) { Text("Place trade") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
