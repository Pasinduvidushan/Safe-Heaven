package lk.safeheaven.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import lk.safeheaven.core.MeshPacket
import kotlin.coroutines.resume

/**
 * One-time onboarding check that BLE advertising works with the phone's current radio state —
 * ideally with Airplane Mode ON and Bluetooth switched back on by hand (BluetoothAdapter.enable()
 * returns false for apps on Android 13+, so the app can never turn Bluetooth on for the user).
 */
object BluetoothSelfTest {
    data class Result(val ok: Boolean, val airplaneMode: Boolean, val message: String)

    @SuppressLint("MissingPermission")
    suspend fun run(context: Context): Result {
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result(false, airplane, "This phone has no Bluetooth adapter.")
        if (!adapter.isEnabled) {
            return Result(
                false, airplane,
                if (airplane) "Bluetooth is off. Airplane Mode switched it off — turn Bluetooth back on in the Bluetooth settings (keep Airplane Mode on), then run the test again."
                else "Bluetooth is off. Turn it on and run the test again.",
            )
        }
        val adv = adapter.bluetoothLeAdvertiser
            ?: return Result(false, airplane, "BLE advertising is not available on this phone.")

        val keys = MeshPacket.Keys(ByteArray(16), ByteArray(16))
        val packet = MeshPacket.encode(keys, 0, 1, 0, MeshPacket.Body.Location(0.0, 0.0, 0, 0, 100, 0))
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false).setTimeout(3_000).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addManufacturerData(MeshPacket.COMPANY_ID, packet).build()

        var cb: AdvertiseCallback? = null
        val startError: Int? = suspendCancellableCoroutine { cont ->
            cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { if (cont.isActive) cont.resume(null) }
                override fun onStartFailure(errorCode: Int) { if (cont.isActive) cont.resume(errorCode) }
            }
            runCatching { adv.startAdvertising(settings, data, cb) }.onFailure { if (cont.isActive) cont.resume(-1) }
        }
        delay(400)
        cb?.let { runCatching { adv.stopAdvertising(it) } }
        return if (startError == null) {
            Result(true, airplane, "Bluetooth advertising works" + if (airplane) " even in Airplane Mode." else ". Run it again with Airplane Mode on to be sure.")
        } else {
            Result(false, airplane, "Advertising failed (code $startError).")
        }
    }
}
