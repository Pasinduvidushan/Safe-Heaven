package lk.safeheaven.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageUtil {
    /** Fraction of the preview occupied by the plate guide (centre band), shared with the overlay. */
    const val GUIDE_WIDTH = 0.84f
    const val GUIDE_HEIGHT = 0.20f

    /** CameraX ImageCapture delivers JPEG: decode, then rotate upright. */
    fun upright(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    /** Crops the centre band that the on-screen guide overlay frames. */
    fun cropToGuide(src: Bitmap): Bitmap {
        val w = (src.width * GUIDE_WIDTH).toInt().coerceAtLeast(1)
        val h = (src.height * GUIDE_HEIGHT).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
    }

    fun toJpeg(bitmap: Bitmap, quality: Int = 88): ByteArray =
        ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
}
