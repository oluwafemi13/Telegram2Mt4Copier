package com.oluwafemi.shots

import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.Channel
import com.oluwafemi.tg2mt5.MetaStatus
import com.oluwafemi.tg2mt5.Mt5Account
import com.oluwafemi.tg2mt5.Signal
import com.oluwafemi.tg2mt5.ui.AccountScreen
import com.oluwafemi.tg2mt5.ui.ActivityScreen
import com.oluwafemi.tg2mt5.ui.AppTheme
import com.oluwafemi.tg2mt5.ui.ChannelEditScreen
import com.oluwafemi.tg2mt5.ui.ChannelScreen
import com.oluwafemi.tg2mt5.ui.MetaApiScreen
import com.oluwafemi.tg2mt5.ui.OverviewScreen
import com.oluwafemi.tg2mt5.ui.SetupScreen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val CHAT = -100123L
private const val CHAT2 = -100456L

/** Seeds sample channels, accounts and history so the screens can be checked by eye. Not shipped in the app. */
fun seed(app: App) {
    app.ledger.clear()
    app.log.clear()
    val now = System.currentTimeMillis() / 1000
    val running = MetaStatus("DEPLOYED", "CONNECTED", "london", "Exness Technologies Ltd", "12345678", "Exness-MT5Real8", 10234.5, 10180.2, "USC", "", System.currentTimeMillis())
    val paused = MetaStatus("UNDEPLOYED", "DISCONNECTED", "london", "", "87654321", "Exness-MT5Real8", null, null, "", "", System.currentTimeMillis())
    app.setups.replaceAll(
        listOf(Channel(CHAT, "Gold VIP Signals", alerts = true), Channel(CHAT2, "FX Daily Setups", alerts = false)),
        listOf(
            Mt5Account(id = "a1", name = "Exness Cent", metaAccountId = "1eda642a", channelId = CHAT, copyOn = true, accountType = "cent", lotMode = "fixed", fixedLot = 1.0, symbolSuffix = "c", status = running),
            Mt5Account(id = "a2", name = "Exness Standard", metaAccountId = "9fbb31c0", channelId = CHAT, copyOn = false, accountType = "standard", lotMode = "risk", riskPercent = 1.0, symbolSuffix = "m", status = paused),
            Mt5Account(id = "a3", name = "Demo account", metaAccountId = "", channelId = null),
        ),
    )
    fun sig(id: Long, ago: Long, buy: Boolean, e: Double) =
        app.ledger.recordSignal("$CHAT:$id", CHAT, "Gold VIP Signals", now - ago, Signal("XAUUSD", buy, e, if (buy) e - 10 else e + 10, listOf(if (buy) e + 10 else e - 10)), "signal")
    sig(1, 6 * 3600, false, 4054.5); app.ledger.recordResult("$CHAT:2", CHAT, "Gold VIP Signals", now - 5 * 3600, "$CHAT:1", true, "TP hit ✅")
    sig(3, 4 * 3600, true, 4040.0); app.ledger.recordResult("$CHAT:4", CHAT, "Gold VIP Signals", now - 3 * 3600, "$CHAT:3", false, "SL hit ❌")
    app.ledger.onDeleted(CHAT, longArrayOf(4))
    sig(5, 2 * 3600, false, 4060.0); app.ledger.setVerified("$CHAT:5", "loss", "Price reached SL 4070 at 28 Oct 8:12 PM")
    app.log.add("signal", "Signal from Gold VIP Signals", "XAUUSD SELL LIMIT @ 4054.5  TP 4044.5  SL 4064.5")
    app.log.add("trade", "Trade placed on Exness Cent: SELL LIMIT XAUUSDc 1 lots @ 4054.5  SL 4064.5  TP 4044.5", "TRADE_RETCODE_PLACED")
    app.log.add("win", "WIN: TP hit · XAUUSDc SELL 1 lots · +990 USC", "Closed at 4044.5 (SL 4064.5, TP 4044.5) on Exness Cent.")
    app.log.add("deleted", "DELETED: channel removed a LOSS result for XAUUSD BUY · Gold VIP Signals", "SL hit ❌")
    app.prefs.metaToken = "sample-token"
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
class ScreenShots {
    private lateinit var app: App

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        seed(app)
    }

    @Test fun overview() = captureRoboImage("overview.png") { AppTheme { OverviewScreen(app, Modifier) {} } }
    @Test fun account() = captureRoboImage("account.png") { AppTheme { AccountScreen(app, Modifier, "a1", null, {}) {} } }
    @Test fun channel() = captureRoboImage("channel_edit.png") { AppTheme { ChannelEditScreen(app, Modifier, CHAT, {}) {} } }
    @Test fun results() = captureRoboImage("results.png") { AppTheme { ChannelScreen(app, Modifier) {} } }
    @Test fun activity() = captureRoboImage("activity.png") { AppTheme { ActivityScreen(app, Modifier) } }
    @Test fun setup() = captureRoboImage("setup.png") { AppTheme { SetupScreen(app, Modifier) {} } }
    @Test fun metaapi() = captureRoboImage("metaapi.png") { AppTheme { MetaApiScreen(app, Modifier, {}) {} } }
}

/** The same Overview on a tablet in landscape, to check the two-column layout. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h1500dp-xhdpi")
class TabletShots {
    @Test
    fun overviewTablet() {
        val app: App = ApplicationProvider.getApplicationContext()
        seed(app)
        captureRoboImage("overview_tablet.png") { AppTheme { OverviewScreen(app, Modifier) {} } }
    }
}
