package com.hybridguard.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** The phone restarted or the app was updated: watch again if the owner wants it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as HybridGuardApp
        val s = app.settings.value
        if (s.monitorEnabled && (s.startOnBoot || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)) MonitorService.sync(app)
    }
}

/** The timer for the next look at the adapter (wakes the phone if it slept). */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Monitor.poke()
    }
}

/** Buttons in the notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as HybridGuardApp
        when (intent.action) {
            Notifier.ACTION_ACK -> Monitor.acknowledge()
            Notifier.ACTION_CHECK -> Monitor.poke(carEvent = true)
            Notifier.ACTION_STOP -> {
                app.settings.update { it.copy(monitorEnabled = false) }
                MonitorService.sync(app)
            }
        }
    }
}

/**
 * The car's Bluetooth connected (or any device: the app keeps the list for
 * the "only while connected to the car" choice), the phone started
 * charging, or Bluetooth was switched on: look for the car now.
 */
class ConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handle(context, intent)
        val app = context.applicationContext as HybridGuardApp
        // Declared in the manifest too, so the car connecting starts the monitor if Android had stopped it.
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED && app.settings.value.monitorEnabled && !Monitor.running) {
            MonitorService.sync(app)
        }
    }

    companion object {
        fun handle(context: Context, intent: Intent) {
            val app = context.applicationContext as HybridGuardApp
            val s = app.settings.value
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = device(intent) ?: return
                    CarLink.connected += device.address
                    if (device.address == s.carDeviceAddress || s.triggerMode == TriggerMode.ALWAYS) Monitor.poke(carEvent = device.address == s.carDeviceAddress)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    device(intent)?.let { CarLink.connected -= it.address }
                }
                Intent.ACTION_POWER_CONNECTED -> if (s.tryOnCharging) Monitor.poke(carEvent = true)
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                        CarLink.refresh(context)
                        Monitor.poke()
                    } else {
                        CarLink.connected.clear()
                    }
                }
            }
        }

        private fun device(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
    }
}
