package lk.safeheaven.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import lk.safeheaven.core.PlateNormalizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Offline plate OCR: ML Kit bundled Latin model, then Sri Lankan normalisation. */
object PlateScanner {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Scan(val rawText: String, val result: PlateNormalizer.Result)

    suspend fun scan(bitmap: Bitmap): Scan = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                cont.resume(Scan(text.text, PlateNormalizer.normalize(text.text)))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
