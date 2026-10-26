package lk.safeheaven.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.SparseArray
import lk.safeheaven.core.MeshPacket
import lk.safeheaven.trip.TripBus

/**
 * Starts a hardware-filtered background scan via PendingIntent so it survives with the screen off.
 * The filter matches manufacturer id 0xFFFF and the magic/version byte, so the OS wakes
 * [MeshScanReceiver] only for Safe Heaven packets.
 */
class MeshScanner(context: Context) {
    private val ctx = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(ctx, MeshScanReceiver::class.java).setAction(ACTION_SCAN_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0x5AFE, intent, flags)
    }

    // Match company id + first byte (magic nibble 0xA, version 1 => high 6 bits 0b101001).
    private fun filters(): List<ScanFilter> {
        val data = byteArrayOf(((MeshPacket.MAGIC shl 4) or (MeshPacket.VERSION shl 2)).toByte())
        val mask = byteArrayOf(0xFC.toByte()) // match the magic nibble + version, ignore the 2 type bits
        return listOf(
            ScanFilter.Builder()
                .setManufacturerData(MeshPacket.COMPANY_ID, data, mask)
                .build(),
        )
    }

    private fun settings(): ScanSettings {
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        if (Build.VERSION.SDK_INT >= 23) {
            b.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }
        if (Build.VERSION.SDK_INT >= 26) b.setLegacy(true)
        return b.build()
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasScanPermission()) return false
        val scanner = adapter?.bluetoothLeScanner ?: return false
        return runCatching {
            scanner.startScan(filters(), settings(), pendingIntent())
            TripBus.log("mesh scan started (PendingIntent, screen-off capable)")
            true
        }.getOrElse {
            TripBus.log("mesh scan failed: ${it.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasScanPermission()) return
        adapter?.bluetoothLeScanner?.let { runCatching { it.stopScan(pendingIntent()) } }
    }

    companion object {
        const val ACTION_SCAN_RESULT = "lk.safeheaven.MESH_SCAN"

        /** Extracts the 0xFFFF manufacturer payload from a scan record's raw bytes. */
        fun manufacturerData(scanRecordBytes: ByteArray?): ByteArray? {
            if (scanRecordBytes == null) return null
            var i = 0
            while (i < scanRecordBytes.size) {
                val len = scanRecordBytes[i].toInt() and 0xFF
                if (len == 0) break
                val type = scanRecordBytes[i + 1].toInt() and 0xFF
                if (type == 0xFF && len >= 3) {
                    val company = (scanRecordBytes[i + 2].toInt() and 0xFF) or
                        ((scanRecordBytes[i + 3].toInt() and 0xFF) shl 8)
                    if (company == MeshPacket.COMPANY_ID) {
                        return scanRecordBytes.copyOfRange(i + 4, i + 1 + len)
                    }
                }
                i += len + 1
            }
            return null
        }
    }
}
