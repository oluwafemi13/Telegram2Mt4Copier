package com.oluwafemi.tg2mt5

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.oluwafemi.tg2mt5.ui.AccountScreen
import com.oluwafemi.tg2mt5.ui.ActivityScreen
import com.oluwafemi.tg2mt5.ui.AddChannelScreen
import com.oluwafemi.tg2mt5.ui.AppTheme
import com.oluwafemi.tg2mt5.ui.ChannelEditScreen
import com.oluwafemi.tg2mt5.ui.ChannelScreen
import com.oluwafemi.tg2mt5.ui.OverviewScreen
import com.oluwafemi.tg2mt5.ui.SetupPage
import com.oluwafemi.tg2mt5.ui.SetupScreen

/**
 * Where the user is: a bottom tab, plus optionally a page on top of it.
 * Pages: "account:<id>", "account:new", "account:new:<chatId>", "channel:<chatId>", "addchannel",
 * and the setup pages "telegram", "metaapi", "alerts", "backup", "test".
 */
object Nav {
    const val OVERVIEW = "overview"
    const val RESULTS = "results"
    const val ACTIVITY = "activity"
    const val SETUP = "setup"
}

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as App

        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableStateOf(Nav.OVERVIEW) }
                var page by rememberSaveable { mutableStateOf<String?>(null) }
                val go: (String?) -> Unit = { page = it }

                BackHandler(enabled = page != null || tab != Nav.OVERVIEW) {
                    if (page != null) page = null else tab = Nav.OVERVIEW
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf(
                                Triple(Nav.OVERVIEW, "Overview", Icons.Filled.Home),
                                Triple(Nav.RESULTS, "Results", Icons.Filled.Star),
                                Triple(Nav.ACTIVITY, "Activity", Icons.Filled.DateRange),
                                Triple(Nav.SETUP, "Setup", Icons.Filled.Settings),
                            ).forEach { (id, label, icon) ->
                                NavigationBarItem(
                                    selected = tab == id && page == null,
                                    onClick = { tab = id; page = null },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    val p = page
                    when {
                        p == null -> when (tab) {
                            Nav.OVERVIEW -> OverviewScreen(app, m, go)
                            Nav.RESULTS -> ChannelScreen(app, m, go)
                            Nav.ACTIVITY -> ActivityScreen(app, m)
                            else -> SetupScreen(app, m, go)
                        }
                        p.startsWith("account:") -> {
                            val parts = p.split(":")
                            val id = parts.getOrNull(1) ?: "new"
                            AccountScreen(app, m, if (id == "new") null else id, parts.getOrNull(2)?.toLongOrNull(), go) { page = null }
                        }
                        p.startsWith("channel:") -> ChannelEditScreen(app, m, p.removePrefix("channel:").toLong(), go) { page = null }
                        p == "addchannel" -> AddChannelScreen(app, m, go) { page = null }
                        else -> SetupPage(app, m, p, go) { page = null }
                    }
                }
            }
        }
    }
}
