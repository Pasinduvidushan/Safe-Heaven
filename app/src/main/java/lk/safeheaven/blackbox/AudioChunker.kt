package lk.safeheaven.blackbox

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * Records sequential 5-second mono AAC chunks (~16 kbps, ~10 KB each) for the evidence vault.
 *
 * Preferred path: MediaRecorder.setNextOutputFile(). When the 5 s max-duration is reached the
 * recorder rolls to the next file without stopping; the NEXT_OUTPUT_FILE_STARTED info event marks
 * the previous file finalised and ready to hand to the hash chain. If a device does not honour that
 * API, it falls back to a plain 5 s stop/restart loop (a few ms gap, still ordered and hashed).
 *
 * Recording is a declared microphone foreground-service capability and runs only while the trip's
 * owner has triggered the alert flow on their own device.
 */
class AudioChunker(
    private val context: Context,
    private val dir: File,
    private val onChunk: (file: File, startedAtMs: Long) -> Unit,
) {
    private val thread = HandlerThread("evidence-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private var recorder: MediaRecorder? = null
    private var index = 0
    private var currentStartedAt = 0L
    private var useNextFile = true
    @Volatile private var running = false

    private fun chunkFile(i: Int) = File(dir, "audio_%06d.m4a".format(i))

    fun start() = handler.post {
        if (running) return@post
        dir.mkdirs()
        running = true
        index = 0
        beginRecorder(chunkFile(0))
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun beginRecorder(first: File) {
        try {
            currentStartedAt = System.currentTimeMillis()
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(16_000)
                setMaxDuration(CHUNK_MS)
                setOutputFile(first.absolutePath)
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> onMaxDuration()
                        MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> onNextFileStarted()
                    }
                }
                prepare()
                start()
            }
            queueNextFile()
        } catch (t: Throwable) {
            // Fall back to stop/restart rollover.
            useNextFile = false
            safeRelease()
            if (running) rolloverRestart(first)
        }
    }

    /** Point the recorder at the next file ahead of time (seamless rollover). */
    private fun queueNextFile() {
        if (!useNextFile) return
        runCatching { recorder?.setNextOutputFile(chunkFile(index + 1)) }
            .onFailure { useNextFile = false }
    }

    private fun onNextFileStarted() {
        // The file at [index] is now closed; the recorder is already writing [index + 1].
        val done = chunkFile(index)
        val startedAt = currentStartedAt
        currentStartedAt = System.currentTimeMillis()
        index++
        if (done.exists() && done.length() > 0) onChunk(done, startedAt)
        queueNextFile()
    }

    private fun onMaxDuration() {
        if (useNextFile) return // handled by NEXT_OUTPUT_FILE_STARTED
        val done = chunkFile(index)
        val startedAt = currentStartedAt
        safeRelease()
        if (done.exists() && done.length() > 0) onChunk(done, startedAt)
        index++
        if (running) rolloverRestart(chunkFile(index))
    }

    private fun rolloverRestart(next: File) = handler.post {
        if (!running) return@post
        currentStartedAt = System.currentTimeMillis()
        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(16_000)
                setMaxDuration(CHUNK_MS)
                setOutputFile(next.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
                }
                prepare()
                start()
            }
        } catch (_: Throwable) {
            safeRelease()
        }
    }

    private fun safeRelease() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    fun stop() = handler.post {
        running = false
        val last = chunkFile(index)
        val startedAt = currentStartedAt
        safeRelease()
        if (last.exists() && last.length() > 0) onChunk(last, startedAt)
        thread.quitSafely()
    }

    companion object {
        const val CHUNK_MS = 5_000
    }
}
