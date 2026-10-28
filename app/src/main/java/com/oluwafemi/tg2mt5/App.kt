package com.oluwafemi.tg2mt5

import android.app.Application

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var log: SignalLog
    lateinit var processor: SignalProcessor
    lateinit var telegram: TelegramManager
    lateinit var trades: TradeStore
    lateinit var ledger: ChannelLedger
    var nativeLibError: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = SignalLog(this)
        trades = TradeStore(this)
        ledger = ChannelLedger(this)
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
}
