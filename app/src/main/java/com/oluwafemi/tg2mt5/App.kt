package com.oluwafemi.tg2mt5

import android.app.Application

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var log: SignalLog
    lateinit var processor: SignalProcessor
    lateinit var telegram: TelegramManager
    lateinit var trades: TradeStore
    lateinit var ledger: ChannelLedger
    lateinit var setups: SetupStore
    var nativeLibError: String? = null

    /** Pairs app: each channel can be linked to only one MT5 account. Flexible app: one channel → many accounts. */
    val strictPairs get() = BuildConfig.STRICT_PAIRS

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = SignalLog(this)
        trades = TradeStore(this)
        ledger = ChannelLedger(this)
        setups = SetupStore(this)
        migrateFromV1()
        processor = SignalProcessor(this)
        telegram = TelegramManager(this)
        Notifier.createChannels(this)
        try {
            telegram.start()
        } catch (e: Throwable) {
            nativeLibError = e.toString()
            log.add("error", "Telegram library failed to load", e.toString())
        }
    }

    /** Version 1.x had one channel list and one MT5 account in its settings. Move them over once; nothing is deleted. */
    private fun migrateFromV1() {
        if (setups.exists) return
        val old = prefs.oldSettings()
        val titles = ledger.signals.value.associate { it.chatId to it.chat }
        val (channels, accounts) = Migration.migrate(old, titles)
        setups.replaceAll(channels, accounts)
        if (channels.isNotEmpty() || accounts.isNotEmpty()) {
            log.add("info", "Updated to version 2: your settings were moved into ${channels.size} channel(s) and ${accounts.size} MT5 account(s)", "Nothing was deleted. Check them on the Overview tab.")
        }
    }
}
