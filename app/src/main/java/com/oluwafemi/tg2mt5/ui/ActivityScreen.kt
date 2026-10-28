package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RESULT_KINDS = setOf("win", "loss", "breakeven", "failed", "skipped", "outcome", "outcome_win", "outcome_loss", "price_win", "price_loss")
private val WARNING_KINDS = setOf("failed", "skipped", "error", "deleted", "edited")

@Composable
fun ActivityScreen(app: App, modifier: Modifier) {
    val entries by app.log.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = when (filter) {
        "trades" -> entries.filter { it.kind in setOf("trade", "win", "loss", "breakeven", "failed", "skipped", "alert") }
        "results" -> entries.filter { it.kind in RESULT_KINDS }
        "warnings" -> entries.filter { it.kind in WARNING_KINDS }
        else -> entries
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { ScreenTitle("Activity", "Everything the copier did, newest first") }
                    if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                }
                Choice(
                    listOf("all" to "All", "trades" to "My trades", "results" to "Results", "warnings" to "Problems"),
                    filter,
                ) { filter = it }
                Gap(4)
                if (shown.isEmpty()) Hint(if (entries.isEmpty()) "Nothing yet. New signals, alerts and trades will appear here." else "Nothing in this view yet.")
            }
        }
        items(shown) { ActivityRow(it) }
        item { Gap(16) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear activity?") },
            text = { Text("This removes the activity list on this phone. The channel record on the Channel tab is kept.") },
            confirmButton = { TextButton(onClick = { app.log.clear(); confirmClear = false }) { Text("Clear", color = BadRed) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

// 12-hour clock, e.g. "28 Oct 9:34:05 PM"
private val timeFmt = SimpleDateFormat("dd MMM h:mm:ss a", Locale.US)

private fun style(kind: String): Pair<String, Color> = when (kind) {
    "trade" -> "TRADE PLACED" to GoodGreen
    "win" -> "WIN" to GoodGreen
    "loss" -> "LOSS" to BadRed
    "breakeven" -> "BREAKEVEN" to SkyBlue
    "failed" -> "FAILED" to BadRed
    "skipped" -> "SKIPPED" to Gold
    "signal" -> "NEW SIGNAL" to Gold
    "alert" -> "ALERT SENT" to Gold
    "outcome_win" -> "CHANNEL · TP HIT" to GoodGreen
    "outcome_loss" -> "CHANNEL · SL HIT" to BadRed
    "outcome" -> "CHANNEL RESULT" to SkyBlue
    "price_win" -> "PRICE CHECK · WIN" to GoodGreen
    "price_loss" -> "PRICE CHECK · LOSS" to BadRed
    "deleted" -> "DELETED BY CHANNEL" to BadRed
    "edited" -> "EDITED BY CHANNEL" to Gold
    "error" -> "PROBLEM" to BadRed
    else -> "INFO" to Muted
}

@Composable
fun ActivityRow(e: LogEntry) {
    val (label, color) = style(e.kind)
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Pill(label, color) }
                Text(timeFmt.format(Date(e.time)), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            // The coloured tag already says WIN / FAILED etc., so drop the same word from the start of the title.
            Text(e.title.replace(Regex("^(WIN|LOSS|BREAKEVEN|FAILED|SKIPPED|DELETED|EDITED|PRICE CHECK): "), ""), style = MaterialTheme.typography.bodyMedium)
            if (e.detail.isNotBlank()) Hint(e.detail)
        }
    }
}
