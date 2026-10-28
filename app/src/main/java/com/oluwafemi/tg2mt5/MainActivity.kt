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
import com.oluwafemi.tg2mt5.ui.ActivityScreen
import com.oluwafemi.tg2mt5.ui.AppTheme
import com.oluwafemi.tg2mt5.ui.ChannelScreen
import com.oluwafemi.tg2mt5.ui.HomeScreen
import com.oluwafemi.tg2mt5.ui.SetupPage
import com.oluwafemi.tg2mt5.ui.SetupScreen

/** Where the user is: a bottom tab, and on the Setup tab optionally one setup page. */
object Nav {
    const val HOME = "home"
    const val CHANNEL = "channel"
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
                var tab by rememberSaveable { mutableStateOf(Nav.HOME) }
                var page by rememberSaveable { mutableStateOf<String?>(null) }
                val open: (String, String?) -> Unit = { t, p -> tab = t; page = p }

                BackHandler(enabled = page != null || tab != Nav.HOME) {
                    if (page != null) page = null else tab = Nav.HOME
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf(
                                Triple(Nav.HOME, "Home", Icons.Filled.Home),
                                Triple(Nav.CHANNEL, "Channel", Icons.Filled.Star),
                                Triple(Nav.ACTIVITY, "Activity", Icons.Filled.DateRange),
                                Triple(Nav.SETUP, "Setup", Icons.Filled.Settings),
                            ).forEach { (id, label, icon) ->
                                NavigationBarItem(
                                    selected = tab == id,
                                    onClick = { tab = id; page = null },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        Nav.HOME -> HomeScreen(app, m, open)
                        Nav.CHANNEL -> ChannelScreen(app, m, open)
                        Nav.ACTIVITY -> ActivityScreen(app, m)
                        else -> page?.let { SetupPage(app, m, it) { page = null } } ?: SetupScreen(app, m) { page = it }
                    }
                }
            }
        }
    }
}
