package com.oluwafemi.tg2mt5.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oluwafemi.tg2mt5.App
import com.oluwafemi.tg2mt5.BuildConfig
import com.oluwafemi.tg2mt5.TgState

/** The Setup tab: a short list of setup areas, each opening its own page. */
@Composable
fun SetupScreen(app: App, modifier: Modifier, openPage: (String) -> Unit) {
    val p = app.prefs
    val tg by app.telegram.state.collectAsStateWithLifecycle()
    val tgReady = tg is TgState.Ready
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Setup", "Tap a section to change it. Changes save automatically.")
        Section("Connections") {
            NavRow(
                "Telegram account and channels",
                if (tgReady) "${(tg as TgState.Ready).name} · ${p.channelIds.size} channel(s) chosen" else "Not connected yet",
                tgReady && p.channelIds.isNotEmpty(),
            ) { openPage("telegram") }
            NavRow(
                "Trading mode and MetaApi",
                if (p.isAuto) "Auto-trade" + if (p.metaToken.isBlank()) " (MetaApi token missing)" else "" else "Alert only",
                !p.isAuto || p.metaToken.isNotBlank(),
            ) { openPage("mode") }
        }
        Section("Trading") {
            NavRow(
                "Account and lot size",
                "${if (p.isCent) "Cent" else "Standard"} account · " + if (p.lotMode == "fixed") "fixed ${p.fixedLotText} lot" else "risk ${p.riskPercentText}% per trade",
                null,
            ) { openPage("lots") }
            NavRow("Symbols and order rules", "Broker symbol names, order type, which TP, safety limits", null) { openPage("symbols") }
        }
        Section("Alerts") {
            NavRow("Notifications and vibration", if (p.extraVibrate) "Strong vibration on" else "Strong vibration off", null) { openPage("alerts") }
        }
        Section("Tools") {
            NavRow("Test a signal", "Paste a message to see how the app reads it", null) { openPage("test") }
        }
        Hint("Version ${BuildConfig.VERSION_NAME}")
        Gap(16)
    }
}

/** One setup page, with a back link to the Setup list. */
@Composable
fun SetupPage(app: App, modifier: Modifier, page: String, onBack: () -> Unit) {
    when (page) {
        "telegram" -> TelegramScreen(app, modifier, onBack)
        "test" -> TestScreen(app, modifier, onBack)
        else -> SettingsScreen(app, modifier, page, onBack)
    }
}
