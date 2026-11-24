package com.hybridguard.app

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive in the background while monitoring is on, and owns
 * the ongoing notification with the live values.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as HybridGuardApp
        try {
            ServiceCompat.startForeground(
                this, Notifier.STATUS_ID, Notifier.status(this, Monitor.status.value, app.settings.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // No Bluetooth permission yet, or a start Android does not allow from the background.
            Log.w("HybridGuard", "Monitor service not started: ${e.message}")
            Monitor.start() // still runs while the app is open
            stopSelf()
            return START_NOT_STICKY
        }
        if (!receiverRegistered) {
            receiverRegistered = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(this, triggers, filter, ContextCompat.RECEIVER_EXPORTED)
            CarLink.refresh(this)
            scope.launch { updateNotifications(app) }
        }
        Monitor.start()
        return START_STICKY
    }

    /** Rebuilds the ongoing notification, at most once per the chosen interval (Android limits the rate too). */
    private suspend fun updateNotifications(app: HybridGuardApp) {
        val nm = getSystemService(NotificationManager::class.java)
        var last = 0L
        var shown: String? = null
        combine(Monitor.status, app.settings.flow) { st, s -> st to s }.collect { (st, s) ->
            if (Notifier.statusKey(st, s) == shown) return@collect
            val wait = s.notifIntervalSec * 1000L - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
            val now = Monitor.status.value
            val settings = app.settings.value
            shown = Notifier.statusKey(now, settings)
            nm.notify(Notifier.STATUS_ID, Notifier.status(this, now, settings))
        }
    }

    private val triggers = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ConnectionReceiver.handle(context, intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (receiverRegistered) runCatching { unregisterReceiver(triggers) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        /** Starts or stops the background monitor to match the settings. */
        fun sync(context: Context) {
            val app = context.applicationContext as HybridGuardApp
            if (app.settings.value.monitorEnabled) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
                } catch (e: Exception) {
                    // Android 12+ refuses some starts from the background; the monitor still runs while the app is open.
                    Log.w("HybridGuard", "Service start refused: ${e.message}")
                    Monitor.start()
                }
            } else {
                Monitor.stop()
                app.stopService(Intent(app, MonitorService::class.java))
            }
        }
    }
}
