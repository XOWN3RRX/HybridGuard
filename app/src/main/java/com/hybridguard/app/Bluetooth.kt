package com.hybridguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hybridguard.obd.ObdTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth classic RFCOMM / SPP to an already-paired adapter (docs/03
 * "Connection choice"). Callers must hold BLUETOOTH_CONNECT on Android 12+.
 */
@SuppressLint("MissingPermission")
class SppTransport(private val context: Context, private val address: String) : ObdTransport {

    @Volatile private var socket: BluetoothSocket? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        // Discovery slows every connection down; this app never scans, but another app may.
        runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }

        // Some adapters only accept one of the two socket kinds.
        val attempts = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var lastError: IOException? = null
        for (create in attempts) {
            val s = create()
            try {
                s.connect()
                socket = s
                return@withContext
            } catch (e: IOException) {
                runCatching { s.close() }
                lastError = e
            }
        }
        throw IOException("Adapter not reachable: ${lastError?.message}", lastError)
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = socket?.outputStream ?: throw IOException("Not connected")
        out.write(bytes)
        out.flush()
    }

    override fun incoming(): Flow<ByteArray> = flow {
        val input = socket?.inputStream ?: throw IOException("Not connected")
        val buffer = ByteArray(1024)
        while (true) {
            val n = input.read(buffer) // throws IOException when the link drops
            if (n < 0) throw IOException("Adapter closed the connection")
            if (n > 0) emit(buffer.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

data class PairedDevice(val name: String, val address: String, val likelyObd: Boolean, val likelyCar: Boolean)

fun bluetoothGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Runtime permissions the app asks for: Bluetooth (Android 12+) and notifications (Android 13+). */
fun runtimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun bluetoothOn(context: Context): Boolean =
    runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

/**
 * Paired (bonded) devices, OBD adapters first, then by name; null while
 * the Bluetooth permission is missing. No scanning.
 */
@SuppressLint("MissingPermission")
fun pairedDevices(context: Context): List<PairedDevice>? {
    if (!bluetoothGranted(context)) return null
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        .map { device ->
            val name = runCatching { device.name }.getOrNull() ?: device.address
            val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            PairedDevice(
                name, device.address,
                likelyObd = looksLikeObd(name, major),
                likelyCar = major == BluetoothClass.Device.Major.AUDIO_VIDEO,
            )
        }
        .sortedWith(compareByDescending<PairedDevice> { it.likelyObd }.thenBy { it.name.lowercase() })
}

/**
 * Bluetooth devices connected to the phone now, for the "only while
 * connected to the car" trigger. Kept from the system's connect and
 * disconnect events ([ConnectionReceiver]) and filled once at start from the
 * hands-free and audio profiles (a car connected before the app started).
 */
object CarLink {
    val connected: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun isConnected(address: String?): Boolean = address != null && address in connected

    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        if (!bluetoothGranted(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.forEach { connected += it.address } }
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            }
        }
    }
}

private val OBD_NAMES = listOf("obd", "vlink", "v-link", "vgate", "elm327", "icar", "konnwei", "veepeak", "obdlink", "scan")

/** By name (OBDII, ELM327, the common adapter brands, ...); headphones, phones, watches and car stereos never count. */
internal fun looksLikeObd(name: String, majorClass: Int?): Boolean {
    val audioPhoneOrWearable = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
        majorClass == BluetoothClass.Device.Major.PHONE ||
        majorClass == BluetoothClass.Device.Major.WEARABLE
    if (audioPhoneOrWearable) return false
    val n = name.lowercase()
    return OBD_NAMES.any { it in n }
}
