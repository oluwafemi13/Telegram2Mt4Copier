package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.ChannelSignal
import com.oluwafemi.tg2mt5.Ledger
import com.oluwafemi.tg2mt5.Nav
import com.oluwafemi.tg2mt5.fmt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelScreen(app: App, modifier: Modifier, open: (String, String?) -> Unit) {
    val signals by app.ledger.signals.collectAsStateWithLifecycle()
    val loose by app.ledger.loose.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf("7d") }
    var chat by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val since = when (period) {
        "today" -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis / 1000
        "7d" -> System.currentTimeMillis() / 1000 - 7 * 86400
        "30d" -> System.currentTimeMillis() / 1000 - 30 * 86400
        else -> 0L
    }
    val st = Ledger.stats(signals, loose, since, chat)
    val chats = signals.map { it.chatId to it.chat }.distinctBy { it.first }
    val list = signals.filter { it.postedAt >= since && (chat == null || it.chatId == chat) }
    val canVerify = app.prefs.verifyPrices && app.prefs.metaToken.isNotBlank() && app.prefs.metaAccountId.isNotBlank()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Channel results", "The app's own record of every signal and result, kept even if the channel deletes it")
        Choice(listOf("today" to "Today", "7d" to "7 days", "30d" to "30 days", "all" to "All time"), period) { period = it }
        if (chats.size > 1) {
            Gap(4)
            Choice(listOf("all" to "All channels") + chats.map { it.first.toString() to it.second }, chat?.toString() ?: "all") {
                chat = it.toLongOrNull()
            }
        }

        if (signals.isEmpty()) {
            Section("No signals recorded yet") {
                Hint(
                    "Turn the copier on and leave it running. Every signal and every TP/SL result your channel posts from now on " +
                        "is saved here the moment it arrives, so you can see the channel's real record.",
                )
            }
            Gap(16)
            return@Column
        }

        // Trust check: what the channel shows vs. what it actually posted vs. what prices say
        Section("Win rate") {
            RateRow("What the channel shows now", st.visibleRate, "${st.visibleWins} TP · ${st.visibleLosses} SL still visible")
            RateRow("Everything the channel posted", st.claimedRate, "${st.claimedWins} TP · ${st.claimedLosses} SL, including deleted")
            if (canVerify || st.priceWins + st.priceLosses > 0) {
                RateRow("What real prices say", st.priceRate, "${st.priceWins} reached TP · ${st.priceLosses} reached SL · ${st.priceOpen} still open")
            } else {
                Hint("Add your MetaApi token (Setup › Trading mode) to also check every signal against real prices. That catches losses the channel never posts.", SkyBlue)
                TextButton(onClick = { open(Nav.SETUP, "mode") }) { Text("Set up MetaApi ›") }
            }
        }

        // Red flags
        val flags = buildList {
            if (st.deletedLossResults > 0) add("${st.deletedLossResults} loss result(s) were deleted after being posted")
            if (st.deletedWinResults > 0) add("${st.deletedWinResults} win result(s) were deleted")
            if (st.deletedSignals > 0) add("${st.deletedSignals} signal(s) were deleted after being posted")
            if (st.editedSignals > 0) add("${st.editedSignals} signal(s) were edited after posting (e.g. SL or TP moved)")
            if (st.silentLosses > 0) add("${st.silentLosses} signal(s) hit SL by price, but no loss is visible in the channel")
            if (st.mismatches > 0) add("${st.mismatches} signal(s) were posted as TP hit, but price reached SL first")
        }
        Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = if (flags.isEmpty()) MaterialTheme.colorScheme.surface else Color(0xFF3A1B1E)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (flags.isEmpty()) "No red flags" else "Red flags", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = if (flags.isEmpty()) GoodGreen else BadRed)
                if (flags.isEmpty()) {
                    Hint("Nothing deleted, edited or hidden in this period, as far as the app has seen.")
                } else {
                    flags.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        Section("Counts") {
            Row(Modifier.fillMaxWidth()) {
                StatTile("${st.signals}", "Signals", SkyBlue, Modifier.weight(1f))
                StatTile("${st.claimedWins}", "TP posted", GoodGreen, Modifier.weight(1f))
                StatTile("${st.claimedLosses}", "SL posted", BadRed, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile("${st.deletedLossResults + st.deletedWinResults}", "Results deleted", if (st.deletedLossResults + st.deletedWinResults > 0) BadRed else Muted, Modifier.weight(1f))
                StatTile("${st.deletedSignals}", "Signals deleted", if (st.deletedSignals > 0) BadRed else Muted, Modifier.weight(1f))
                StatTile("${st.noResultPosted}", "No result posted", if (st.noResultPosted > 0) Gold else Muted, Modifier.weight(1f))
            }
        }

        Gap(4)
        Text("Signals (${list.size})", style = MaterialTheme.typography.titleMedium, color = Gold, fontWeight = FontWeight.SemiBold)
        Hint("Newest first. Each shows what the channel said and, if available, what price actually did.")
        list.take(100).forEach { SignalCard(it) }

        Gap(8)
        TextButton(onClick = { confirmClear = true }) { Text("Clear channel record", color = BadRed) }
        Gap(16)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the channel record?") },
            text = { Text("All recorded signals and results on this phone will be removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { app.ledger.clear(); confirmClear = false }) { Text("Clear", color = BadRed) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RateRow(label: String, rate: Int?, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(rate?.let { "$it%" } ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = when { rate == null -> Muted; rate >= 60 -> GoodGreen; rate >= 45 -> Gold; else -> BadRed })
        }
        LinearProgressIndicator(
            progress = { (rate ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = GoodGreen,
            trackColor = BadRed.copy(alpha = 0.35f),
        )
        Hint(detail)
    }
}

// 12-hour clock
private val sigTime = SimpleDateFormat("d MMM, h:mm a", Locale.US)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalCard(s: ChannelSignal) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.symbol} ${s.direction}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(sigTime.format(Date(s.postedAt * 1000)), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Hint("${s.entry?.let { "Entry ${fmt(it)} · " } ?: ""}SL ${fmt(s.stopLoss)} · TP ${fmt(s.takeProfit)} · ${s.chat}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (s.claim) {
                    "win" -> Pill(if (s.claimDeletedAt > 0) "Channel: TP hit (deleted)" else "Channel: TP hit", if (s.claimDeletedAt > 0) Gold else GoodGreen)
                    "loss" -> Pill(if (s.claimDeletedAt > 0) "Channel: SL hit (DELETED)" else "Channel: SL hit", BadRed)
                    "unclear" -> Pill("Channel: result posted", SkyBlue)
                    else -> Pill("Channel: no result", Muted)
                }
                when (s.verified) {
                    "win" -> Pill("Price: TP reached", GoodGreen)
                    "loss" -> Pill("Price: SL reached", BadRed)
                    "both" -> Pill("Price: unclear", Gold)
                    "expired" -> Pill("Price: neither in 7 days", Muted)
                }
                if (s.signalDeletedAt > 0) Pill("Signal DELETED", BadRed)
                if (s.signalEditedAt > 0) Pill("Signal edited", Gold)
                if (s.claimEditedAt > 0) Pill("Result edited", Gold)
            }
            if (s.verifyNote.isNotBlank()) Hint(s.verifyNote)
        }
    }
}
