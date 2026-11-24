package com.hybridguard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridguard.app.ui.AppTheme
import com.hybridguard.app.ui.BatteryScreen
import com.hybridguard.app.ui.LiveScreen
import com.hybridguard.app.ui.ModelYearPrompt
import com.hybridguard.app.ui.SessionDetailScreen
import com.hybridguard.app.ui.SessionsScreen
import com.hybridguard.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val s by app.settings.flow.collectAsStateWithLifecycle()
            val dark = when (s.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            AppTheme(dark) { HybridGuardUi() }
        }
    }
}

private enum class Tab(val title: String) { LIVE("Live"), BATTERY("Battery"), SESSIONS("Sessions"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HybridGuardUi() {
    val context = LocalContext.current
    val app = context.app
    var tab by rememberSaveable { mutableStateOf(if (app.settings.value.hasAdapter) Tab.LIVE else Tab.SETTINGS) }
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }

    // Bluetooth and notifications are asked for once, then the monitor looks for the car.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MonitorService.sync(context)
        Monitor.poke()
    }
    LaunchedEffect(Unit) {
        if (!bluetoothGranted(context) || !notificationsGranted(context)) ask.launch(runtimePermissions())
        else MonitorService.sync(context)
    }

    ModelYearPrompt()
    if (openSession != null) BackHandler { openSession = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (openSession != null) "Session" else "HybridGuard · " + tab.title) },
                navigationIcon = {
                    if (openSession != null) {
                        IconButton(onClick = { openSession = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && openSession == null,
                        onClick = { tab = t; openSession = null },
                        icon = {
                            when (t) {
                                Tab.LIVE -> Icon(Icons.Filled.Home, t.title)
                                Tab.BATTERY -> Icon(painterResource(R.drawable.ic_tab_battery), t.title)
                                Tab.SESSIONS -> Icon(Icons.AutoMirrored.Filled.List, t.title)
                                Tab.SETTINGS -> Icon(Icons.Filled.Settings, t.title)
                            }
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val id = openSession
            when {
                id != null -> SessionDetailScreen(id, onDeleted = { openSession = null })
                tab == Tab.LIVE -> LiveScreen(onOpenSettings = { tab = Tab.SETTINGS }, onOpenSession = { openSession = it })
                tab == Tab.BATTERY -> BatteryScreen()
                tab == Tab.SESSIONS -> SessionsScreen(onOpen = { openSession = it })
                else -> SettingsScreen()
            }
        }
    }
}
