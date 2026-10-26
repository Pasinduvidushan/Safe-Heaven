package lk.safeheaven.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import lk.safeheaven.core.MeshPacket
import lk.safeheaven.trip.TripBus

/**
 * Broadcasts the 27-byte payload as manufacturer-specific data (test company id 0xFFFF) in a
 * non-connectable, legacy advertisement. One packet is advertised at a time; the caller alternates
 * Type A (location) and Type B (plate) every second.
 */
class MeshAdvertiser(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val ctx = context.applicationContext
    private var advertiser: BluetoothLeAdvertiser? = null
    private var active: AdvertiseCallback? = null

    val supported: Boolean
        get() = adapter?.isMultipleAdvertisementSupported == true

    private fun hasPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun broadcast(packet: ByteArray) {
        if (!hasPermission()) return
        val adv = advertiser ?: adapter?.bluetoothLeAdvertiser?.also { advertiser = it } ?: return
        active?.let { runCatching { adv.stopAdvertising(it) } }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(MeshPacket.COMPANY_ID, packet)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                TripBus.log("BLE advertise failed: $errorCode")
            }
        }
        runCatching { adv.startAdvertising(settings, data, cb) }
            .onSuccess { active = cb }
            .onFailure { TripBus.log("BLE advertise error: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasPermission()) return
        active?.let { cb -> runCatching { advertiser?.stopAdvertising(cb) } }
        active = null
    }
}
