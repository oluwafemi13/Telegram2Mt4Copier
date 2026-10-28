package com.oluwafemi.tg2mt5

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.oluwafemi.tg2mt5.ui.AppTheme
import com.oluwafemi.tg2mt5.ui.HomeScreen
import com.oluwafemi.tg2mt5.ui.SettingsScreen
import com.oluwafemi.tg2mt5.ui.TelegramScreen
import com.oluwafemi.tg2mt5.ui.TestScreen

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as App

        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(
                                Triple("Home", Icons.Filled.Home, 0),
                                Triple("Telegram", Icons.Filled.Send, 1),
                                Triple("Settings", Icons.Filled.Settings, 2),
                                Triple("Test", Icons.Filled.Build, 3),
                            )
                            items.forEach { (label, icon, i) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> HomeScreen(app, m, onGoTelegram = { tab = 1 }, onGoSettings = { tab = 2 })
                        1 -> TelegramScreen(app, m)
                        2 -> SettingsScreen(app, m)
                        else -> TestScreen(app, m)
                    }
                }
            }
        }
    }
}
