package com.oluwafemi.tg2mt5

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed class TgState {
    object NoCredentials : TgState()
    object Starting : TgState()
    object WaitPhone : TgState()
    data class WaitCode(val phone: String) : TgState()
    data class WaitPassword(val hint: String) : TgState()
    data class Ready(val name: String) : TgState()
    data class NeedsOfficialApp(val reason: String) : TgState()
    object Closed : TgState()
}

data class ChatItem(val id: Long, val title: String, val kind: String)

/** Runs Telegram (TDLib) inside the app, logged into the user's own account. */
class TelegramManager(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: Client? = null
    private var restartAfterClose = false
    private val knownChats = ConcurrentHashMap<Long, TdApi.Chat>()

    private val _state = MutableStateFlow<TgState>(TgState.NoCredentials)
    val state: StateFlow<TgState> = _state
    private val _chats = MutableStateFlow<List<ChatItem>>(emptyList())
    val chats: StateFlow<List<ChatItem>> = _chats
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    @Synchronized
    fun start() {
        if (client != null) return
        if (app.prefs.tgApiId.toIntOrNull() == null || app.prefs.tgApiHash.isBlank()) {
            _state.value = TgState.NoCredentials
            return
        }
        _state.value = TgState.Starting
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (_: Throwable) {
        }
        client = Client.create({ onUpdate(it) }, { e -> app.log.add("error", "Telegram error", e.toString()) }, null)
    }

    /** Re-creates the Telegram connection, e.g. after changing the API ID. */
    fun restart() {
        val c = synchronized(this) { client }
        if (c == null) {
            start()
        } else {
            restartAfterClose = true
            c.send(TdApi.Close(), null)
        }
    }

    private fun send(f: TdApi.Function<*>, onResult: (TdApi.Object) -> Unit = {}) {
        val c = client ?: return
        c.send(f) { result ->
            if (result is TdApi.Error) {
                _lastError.value = friendly(result)
            }
            onResult(result)
        }
    }

    private fun friendly(e: TdApi.Error): String = when {
        e.message.contains("PHONE_CODE_INVALID") -> "Wrong login code. Try again."
        e.message.contains("PASSWORD_HASH_INVALID") -> "Wrong 2-step verification password."
        e.message.contains("PHONE_NUMBER_INVALID") -> "Phone number not valid. Use international format, e.g. +2348012345678."
        e.message.contains("API_ID_INVALID") -> "API ID / API hash not valid. Check them on my.telegram.org."
        e.message.contains("FLOOD_WAIT") -> "Too many attempts. Telegram asks you to wait before trying again."
        else -> "Telegram: ${e.message}"
    }

    fun submitPhone(phone: String) {
        _lastError.value = null
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            authenticationTokens = arrayOf()
        }
        send(TdApi.SetAuthenticationPhoneNumber(phone.replace(" ", ""), settings))
    }

    fun submitCode(code: String) {
        _lastError.value = null
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    fun submitPassword(password: String) {
        _lastError.value = null
        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun logOut() = send(TdApi.LogOut())

    fun refreshChats() {
        for (list in listOf<TdApi.ChatList>(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
            send(TdApi.LoadChats(list, 500))
            send(TdApi.GetChats(list, 500)) { r ->
                if (r is TdApi.Chats) {
                    r.chatIds.forEach { id -> if (!knownChats.containsKey(id)) send(TdApi.GetChat(id)) { c -> if (c is TdApi.Chat) addChat(c) } }
                    publishChats()
                }
            }
        }
    }

    private fun addChat(chat: TdApi.Chat) {
        knownChats[chat.id] = chat
        publishChats()
    }

    private fun publishChats() {
        val order = mapOf("Channel" to 0, "Group" to 1, "Private" to 2)
        _chats.value = knownChats.values.map { ChatItem(it.id, it.title, kindOf(it.type)) }
            .sortedWith(compareBy({ order[it.kind] ?: 3 }, { it.title.lowercase(Locale.ROOT) }))
    }

    private fun kindOf(t: TdApi.ChatType?): String = when (t) {
        is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Group"
        is TdApi.ChatTypeBasicGroup -> "Group"
        is TdApi.ChatTypePrivate -> "Private"
        else -> "Other"
    }

    fun chatTitle(id: Long): String = knownChats[id]?.title ?: "chat $id"

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuth(obj.authorizationState)
            is TdApi.UpdateNewChat -> addChat(obj.chat)
            is TdApi.UpdateChatTitle -> knownChats[obj.chatId]?.let { it.title = obj.title; publishChats() }
            is TdApi.UpdateNewMessage -> onMessage(obj.message)
            is TdApi.UpdateDeleteMessages ->
                if (obj.isPermanent && !obj.fromCache && obj.chatId in app.prefs.channelIds) app.processor.onDeleted(obj.chatId, obj.messageIds)
            is TdApi.UpdateMessageContent ->
                if (obj.chatId in app.prefs.channelIds) textOf(obj.newContent)?.let { app.processor.onEdited(obj.chatId, obj.messageId, it) }
            else -> {}
        }
    }

    private fun onMessage(m: TdApi.Message) {
        if (m.chatId !in app.prefs.channelIds) return
        val text = textOf(m.content)
        if (text.isNullOrBlank()) return
        val age = System.currentTimeMillis() / 1000 - m.date
        val source = chatTitle(m.chatId)
        val reply = m.replyTo as? TdApi.MessageReplyToMessage
        val replyKey = reply?.let { "${if (it.chatId != 0L) it.chatId else m.chatId}:${it.messageId}" }
        scope.launch {
            try {
                app.processor.onMessage(text, source, "${m.chatId}:${m.id}", age, replyKey, m.chatId, m.date.toLong())
            } catch (e: Exception) {
                app.log.add("error", "Failed to process message from $source", e.toString())
            }
        }
    }

    private fun textOf(c: TdApi.MessageContent?): String? = when (c) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        else -> null
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dir = File(app.filesDir, "tdlib").absolutePath
                val params = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = dir
                    filesDirectory = "$dir/files"
                    databaseEncryptionKey = ByteArray(0)
                    useFileDatabase = false
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = app.prefs.tgApiId.toInt()
                    apiHash = app.prefs.tgApiHash
                    systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" }
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                    systemVersion = "Android ${Build.VERSION.RELEASE}"
                    applicationVersion = BuildConfig.VERSION_NAME
                }
                send(params)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _state.value = TgState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> _state.value = TgState.WaitCode(s.codeInfo.phoneNumber)
            is TdApi.AuthorizationStateWaitPassword -> _state.value = TgState.WaitPassword(s.passwordHint)
            is TdApi.AuthorizationStateReady -> {
                _lastError.value = null
                send(TdApi.GetMe()) { u ->
                    val name = if (u is TdApi.User) listOf(u.firstName, u.lastName).filter { it.isNotBlank() }.joinToString(" ") else "your account"
                    _state.value = TgState.Ready(name)
                }
                _state.value = TgState.Ready("…")
                refreshChats()
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _state.value = TgState.NeedsOfficialApp("Confirm this login from another device where you are logged in.")
            is TdApi.AuthorizationStateWaitRegistration ->
                _state.value = TgState.NeedsOfficialApp("This phone number has no Telegram account. Sign up in the official Telegram app first.")
            is TdApi.AuthorizationStateWaitEmailAddress, is TdApi.AuthorizationStateWaitEmailCode ->
                _state.value = TgState.NeedsOfficialApp("Telegram asks for an email login step. Set up a login email in the official Telegram app (Settings → Privacy and Security), then try again.")
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> _state.value = TgState.Starting
            is TdApi.AuthorizationStateClosed -> {
                synchronized(this) { client = null }
                knownChats.clear()
                _chats.value = emptyList()
                _state.value = TgState.Closed
                // After a log-out or restart, start a fresh session so the login screen appears again.
                restartAfterClose = false
                start()
            }
            else -> _state.value = TgState.NeedsOfficialApp("Telegram needs an extra login step (${s.javaClass.simpleName}). Please finish it in the official app and try again.")
        }
    }
}
