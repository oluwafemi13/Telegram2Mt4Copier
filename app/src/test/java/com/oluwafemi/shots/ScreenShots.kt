package com.oluwafemi.shots

import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.Signal
import com.oluwafemi.tg2mt5.ui.ActivityScreen
import com.oluwafemi.tg2mt5.ui.AppTheme
import com.oluwafemi.tg2mt5.ui.ChannelScreen
import com.oluwafemi.tg2mt5.ui.HomeScreen
import com.oluwafemi.tg2mt5.ui.SettingsScreen
import com.oluwafemi.tg2mt5.ui.SetupScreen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens with sample data so they can be checked by eye. Not shipped in the app. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenShots {
    private lateinit var app: App

    @Before
    fun seed() {
        app = ApplicationProvider.getApplicationContext()
        app.ledger.clear()
        app.log.clear()
        val now = System.currentTimeMillis() / 1000
        val chat = -100123L
        fun sig(id: Long, ago: Long, buy: Boolean, e: Double) =
            app.ledger.recordSignal("$chat:$id", chat, "Gold VIP Signals", now - ago, Signal("XAUUSD", buy, e, if (buy) e - 10 else e + 10, listOf(if (buy) e + 10 else e - 10)), "signal")
        sig(1, 6 * 3600, false, 4054.5); app.ledger.recordResult("$chat:2", chat, "Gold VIP Signals", now - 5 * 3600, "$chat:1", true, "TP hit ✅")
        sig(3, 4 * 3600, true, 4040.0); app.ledger.recordResult("$chat:4", chat, "Gold VIP Signals", now - 3 * 3600, "$chat:3", false, "SL hit ❌")
        app.ledger.onDeleted(chat, longArrayOf(4))
        sig(5, 2 * 3600, false, 4060.0); app.ledger.setVerified("$chat:5", "loss", "Price reached SL 4070 at 28 Oct 8:12 PM")
        sig(6, 1800, true, 4050.0)
        app.log.add("signal", "Signal from Gold VIP Signals", "XAUUSD SELL @ 4054.5  TP 4044.5  SL 4064.5")
        app.log.add("trade", "Trade placed: ORDER_TYPE_SELL XAUUSDm 0.01 lots (market ~4054.3)  SL 4064.5  TP 4044.5", "TRADE_RETCODE_DONE")
        app.log.add("win", "WIN: TP hit · XAUUSDm SELL 0.01 lots · +9.90 USD", "Closed at 4044.5 (SL 4064.5, TP 4044.5).")
        app.log.add("deleted", "DELETED: channel removed a LOSS result for XAUUSD BUY · Gold VIP Signals", "SL hit ❌")
        app.log.add("failed", "FAILED: MT5 rejected · ORDER_TYPE_BUY XAUUSDm 0.01 lots", "TRADE_RETCODE_NO_MONEY Not enough money")
        app.prefs.channelIds = setOf(chat)
    }

    @Test
    fun home() = captureRoboImage("home.png") { AppTheme { HomeScreen(app, Modifier) { _, _ -> } } }

    @Test
    fun channel() = captureRoboImage("channel.png") { AppTheme { ChannelScreen(app, Modifier) { _, _ -> } } }

    @Test
    fun activity() = captureRoboImage("activity.png") { AppTheme { ActivityScreen(app, Modifier) } }

    @Test
    fun setup() = captureRoboImage("setup.png") { AppTheme { SetupScreen(app, Modifier) {} } }

    @Test
    fun settingsMode() = captureRoboImage("settings_mode.png") { AppTheme { SettingsScreen(app, Modifier, "mode") {} } }
}
