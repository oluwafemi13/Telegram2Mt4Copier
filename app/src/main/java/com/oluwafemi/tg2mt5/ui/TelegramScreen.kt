package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
fun TelegramScreen(app: App, modifier: Modifier, onBack: () -> Unit) {
    val state by app.telegram.state.collectAsStateWithLifecycle()
    val error by app.telegram.lastError.collectAsStateWithLifecycle()
    val chats by app.telegram.chats.collectAsStateWithLifecycle()
    var editCreds by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(app.prefs.channelIds) }
    var filter by remember { mutableStateOf("") }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenTitle("Telegram", "The app reads your signal channel through your own Telegram account.", onBack)
        }

        if (state == TgState.NoCredentials || editCreds) {
            item { CredentialsSection(app) { editCreds = false } }
        }

        item {
            Column {
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
                        Text(s.name)
                        Row {
                            TextButton(onClick = { app.telegram.refreshChats() }) { Text("Refresh chats") }
                            TextButton(onClick = { editCreds = true }) { Text("API settings") }
                            TextButton(onClick = { app.telegram.logOut() }) { Text("Log out", color = BadRed) }
                        }
                    }
                }
            }
        }

        if (state is TgState.Ready) {
            item {
                Column {
                    Gap()
                    Text("Your signal channels (${selected.size} chosen)", style = MaterialTheme.typography.titleMedium, color = Gold)
                    Hint("Tick the channel(s) that post your signals. Only these are read; ticked ones are shown first.")
                    Field("Search", filter, { filter = it })
                }
            }
            val shown = chats.filter { filter.isBlank() || it.title.contains(filter, ignoreCase = true) }
                .sortedByDescending { it.id in selected }
            items(shown, key = { it.id }) { chat ->
                ChatRow(chat, chat.id in selected) { on ->
                    selected = if (on) selected + chat.id else selected - chat.id
                    app.prefs.channelIds = selected
                }
            }
            if (chats.isEmpty()) item { Hint("Loading your chats… tap Refresh chats if nothing appears.") }
        }
        item { Gap(16) }
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
private fun ChatRow(chat: ChatItem, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(chat.title)
            Hint(chat.kind)
        }
    }
}
