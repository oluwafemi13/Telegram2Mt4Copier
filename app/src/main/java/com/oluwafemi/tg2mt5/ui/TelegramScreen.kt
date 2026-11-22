package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.ChatItem
import com.oluwafemi.tg2mt5.TgState

@Composable
fun TelegramScreen(app: App, modifier: Modifier, go: (String?) -> Unit, onBack: () -> Unit) {
    val state by app.telegram.state.collectAsStateWithLifecycle()
    val error by app.telegram.lastError.collectAsStateWithLifecycle()
    val channels by app.setups.channels.collectAsStateWithLifecycle()
    var editCreds by remember { mutableStateOf(false) }

    Page(modifier) {
        ScreenTitle("Telegram", "The app reads your signal channels through your own Telegram account. You log in once; then add as many channels as you like.", onBack)
        if (state == TgState.NoCredentials || editCreds) CredentialsSection(app) { editCreds = false }
        error?.let { Hint(it, BadRed); Gap() }
        when (val s = state) {
            TgState.NoCredentials -> {}
            TgState.Starting, TgState.Closed -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(8.dp)); Text("Connecting to Telegram…")
            }
            TgState.WaitPhone -> OneField(
                "Log in", "Phone number (with country code, e.g. +2348012345678)", "Send code", number = true,
                hint = "Telegram will send a login code to your Telegram app (or by SMS).",
            ) { app.telegram.submitPhone(it) }
            is TgState.WaitCode -> OneField(
                "Enter login code", "Code sent to ${s.phone}", "Log in", number = true,
                hint = "Check your Telegram app for a message from Telegram with the code.",
            ) { app.telegram.submitCode(it) }
            is TgState.WaitPassword -> OneField(
                "2-step verification", "Your Telegram cloud password", "Log in", secret = true,
                hint = if (s.hint.isNotBlank()) "Password hint: ${s.hint}" else "Your account has a 2-step verification password.",
            ) { app.telegram.submitPassword(it) }
            is TgState.NeedsOfficialApp -> Section("Extra step needed") {
                Hint(s.reason)
                OutlinedButton(onClick = { app.telegram.restart() }) { Text("Try again") }
            }
            is TgState.Ready -> Section("Logged in") {
                StatusRowText("✓ ${s.name}", GoodGreen)
                Row {
                    TextButton(onClick = { app.telegram.refreshChats() }) { Text("Refresh chats") }
                    TextButton(onClick = { editCreds = true }) { Text("API settings") }
                    TextButton(onClick = { app.telegram.logOut() }) { Text("Log out", color = BadRed) }
                }
            }
        }
        if (state is TgState.Ready) {
            Section("Your signal channels (${channels.size})") {
                if (channels.isEmpty()) Hint("No channels yet.")
                channels.forEach { c -> NavRow(c.title, if (c.alerts) "Alerts on" else "Alerts off", null) { go("channel:${c.chatId}") } }
                Button(onClick = { go("addchannel") }) { Text("+ Add a channel") }
            }
        }
        Gap(16)
    }
}

@Composable
private fun StatusRowText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
}

/** Pick one of your Telegram chats to add as a signal channel. */
@Composable
fun AddChannelScreen(app: App, modifier: Modifier, go: (String?) -> Unit, onBack: () -> Unit) {
    val state by app.telegram.state.collectAsStateWithLifecycle()
    val chats by app.telegram.chats.collectAsStateWithLifecycle()
    val channels by app.setups.channels.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("") }
    val added = channels.map { it.chatId }.toSet()

    androidx.compose.foundation.layout.Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ScreenTitle("Add a channel", "Tap the Telegram channel or group that posts your signals.", onBack) }
            if (state !is TgState.Ready) {
                item {
                    Section("Log in to Telegram first") {
                        Hint("The app needs your Telegram login to list your channels.")
                        Button(onClick = { go("telegram") }) { Text("Log in to Telegram") }
                    }
                }
            } else {
                item {
                    Column {
                        Field("Search your chats", filter, { filter = it })
                        if (chats.isEmpty()) Hint("Loading your chats…")
                    }
                }
                val shown = chats.filter { filter.isBlank() || it.title.contains(filter, ignoreCase = true) }
                items(shown, key = { it.id }) { chat ->
                    ChatRow(chat, chat.id in added) {
                        if (chat.id !in added) {
                            app.setups.saveChannel(com.oluwafemi.tg2mt5.Channel(chat.id, chat.title))
                            app.log.add("info", "Added channel ${chat.title}", "")
                        }
                        go("channel:${chat.id}")
                    }
                }
            }
            item { Gap(16) }
        }
    }
}

@Composable
private fun CredentialsSection(app: App, onDone: () -> Unit) {
    var id by remember { mutableStateOf(app.prefs.tgApiId) }
    var hash by remember { mutableStateOf(app.prefs.tgApiHash) }
    Section("Telegram API ID (one-time setup)") {
        Hint(
            "1. Open my.telegram.org in a browser and log in with your phone number.\n" +
                "2. Tap “API development tools”.\n" +
                "3. Fill in any app title and short name (e.g. TG2MT5 Copier), platform Android, and create it.\n" +
                "4. Copy the “App api_id” and “App api_hash” here.\n" +
                "Keep these private: they are tied to your Telegram account.",
        )
        Field("API ID (numbers)", id, { id = it.filter(Char::isDigit) }, number = true)
        Field("API hash", hash, { hash = it.trim() })
        Button(
            enabled = id.isNotBlank() && hash.length >= 20,
            onClick = {
                app.prefs.tgApiId = id
                app.prefs.tgApiHash = hash
                app.telegram.restart()
                onDone()
            },
        ) { Text("Save and connect") }
    }
}

@Composable
private fun OneField(title: String, label: String, button: String, number: Boolean = false, secret: Boolean = false, hint: String, onSubmit: (String) -> Unit) {
    var v by remember(title) { mutableStateOf("") }
    Section(title) {
        Hint(hint)
        Field(label, v, { v = it }, number = number && !label.startsWith("Phone"), secret = secret)
        Button(enabled = v.isNotBlank(), onClick = { onSubmit(v) }) { Text(button) }
    }
}

@Composable
private fun ChatRow(chat: ChatItem, added: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(chat.title, style = MaterialTheme.typography.bodyLarge)
            Hint(chat.kind)
        }
        if (added) Pill("Added", GoodGreen) else Text("+ Add", color = SkyBlue)
    }
}
