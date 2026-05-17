package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SpeechCaptureService  —  Word-streaming real-time build
 *
 * Audio pipeline:
 *   1. Capture 1 s of internal device audio (no microphone) via
 *      AudioPlaybackCaptureConfiguration + MediaProjection
 *   2. Wrap PCM in a WAV header
 *   3. POST to whisper_server.py → receive full Hindi sentence JSON
 *   4. Split sentence into individual words
 *   5. Post each word to OverlayService at WORD_INTERVAL_MS intervals
 *      so the subtitle types out word-by-word in real time
 *
 * OverlayService owns all line/wrap/read-pause logic.
 * This service only feeds it one word at a time.
 */
class SpeechCaptureService : Service() {

    companion object {
        const val CHANNEL_ID        = "speech_capture_channel"
        const val NOTIF_ID          = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var targetLanguage = "hindi"
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""

        private const val TAG         = "SpeechCapture"
        private const val SAMPLE_RATE = 16_000
        private const val WHISPER_URL = "http://127.0.0.1:8765/transcribe"

        // 1-second audio chunks → first subtitle within ~1-2 s of speech
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 1       // 16 000 samples
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2     // 32 000 bytes

        // 0.5 s carried into next chunk for sentence-boundary context
        private const val OVERLAP_BYTES = SAMPLE_RATE / 2 * 2  // 16 000 bytes

        // Delay between successive words posted to the overlay.
        // 180 ms ≈ natural spoken cadence (~330 wpm).
        private const val WORD_INTERVAL_MS = 180L

        // Suppress identical consecutive results within this window
        private const val DEDUP_WINDOW_MS = 1_200L
    }

    private val mainHandler    = Handler(Looper.getMainLooper())
    private val capturing      = AtomicBoolean(false)
    private var captureThread:   Thread?           = null
    private var audioRecord:     AudioRecord?      = null
    private var mediaProjection: MediaProjection?  = null
    private var wakeLock:        PowerManager.WakeLock? = null

    // 2 threads: chunk N+1 uploads while Whisper processes chunk N
    private val whisperExecutor = Executors.newFixedThreadPool(2)

    private var lastPushedHindi  = ""
    private var lastPushedTimeMs = 0L

    // Pending word-stream runnables — cancelled when a newer result arrives
    private val pendingWordRunnables = mutableListOf<Runnable>()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CaptionLens::SpeechCapture")
            .also { it.acquire(60 * 60 * 1000L) }
        Log.d(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No MediaProjection token"); stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection: ${e.message}"); stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) { stopSelf(); return START_NOT_STICKY }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { mainHandler.post { stopSelf() } }
            }, Handler(Looper.getMainLooper()))
        }

        startCapture()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        capturing.set(false)
        captureThread?.interrupt(); captureThread = null
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        whisperExecutor.shutdownNow()
        cancelWordStream()
        mainHandler.removeCallbacksAndMessages(null)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    // ── Audio capture ──────────────────────────────────────────────────────────

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10+ required."); stopSelf(); return
        }
        val projection = mediaProjection ?: run {
            OverlayService.updateText("", "Screen capture lost."); stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            OverlayService.updateText("", "Audio init failed."); stopSelf(); return
        }
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 2)

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            OverlayService.updateText("", "Audio setup failed: ${e.message}"); stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); OverlayService.updateText("", "Audio init failed."); stopSelf(); return
        }

        audioRecord = ar
        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio to Hindi…")
        OverlayService.updateText("", "Listening…")
        Log.d(TAG, "Capture started — chunk=1s overlap=${OVERLAP_BYTES}B buf=${bufSize}B")

        captureThread = Thread({
            val chunkBuf = ByteArray(CHUNK_BYTES)
            var chunkPos = 0
            val readBuf  = ByteArray(4096)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)

                if (read == AudioRecord.ERROR_INVALID_OPERATION
                    || read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord.read error: $read"); break
                }
                if (read <= 0) continue

                var src = 0
                while (src < read) {
                    val toCopy = minOf(read - src, CHUNK_BYTES - chunkPos)
                    System.arraycopy(readBuf, src, chunkBuf, chunkPos, toCopy)
                    chunkPos += toCopy
                    src      += toCopy

                    if (chunkPos >= CHUNK_BYTES) {
                        val payload = chunkBuf.copyOf(CHUNK_BYTES)
                        // Sliding window: carry last 0.5 s into next chunk
                        System.arraycopy(
                            chunkBuf, CHUNK_BYTES - OVERLAP_BYTES,
                            chunkBuf, 0, OVERLAP_BYTES
                        )
                        chunkPos = OVERLAP_BYTES
                        if (!whisperExecutor.isShutdown)
                            whisperExecutor.submit { sendToWhisper(payload) }
                    }
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    // ── Whisper HTTP ───────────────────────────────────────────────────────────

    private fun sendToWhisper(pcmBytes: ByteArray) {
        try {
            val wavBytes = pcmToWav(pcmBytes)
            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.doOutput       = true
            conn.connectTimeout = 3_000
            conn.readTimeout    = 8_000
            conn.outputStream.use { it.write(wavBytes) }

            if (conn.responseCode != 200) return

            val body      = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json      = JSONObject(body)
            val hindiText = json.optString("text",        "").trim()
            val srcText   = json.optString("source_text", "").trim()
            val lang      = json.optString("language",    "")
            val conf      = json.optDouble("confidence",   0.0)

            if (hindiText.length < 2) return

            val now = System.currentTimeMillis()
            if (hindiText == lastPushedHindi && (now - lastPushedTimeMs) < DEDUP_WINDOW_MS) return

            lastPushedHindi  = hindiText
            lastPushedTimeMs = now
            latestOriginal   = srcText
            latestEnglish    = srcText
            latestHindi      = hindiText

            Log.d(TAG, "[$lang ${(conf * 100).toInt()}%] ${hindiText.take(60)}")

            mainHandler.post {
                MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
            }

            streamWords(srcText, hindiText)

        } catch (e: Exception) {
            Log.w(TAG, "Whisper: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Word streaming ─────────────────────────────────────────────────────────

    /**
     * Splits [hindiText] into words and posts each one to [OverlayService.appendWord]
     * at [WORD_INTERVAL_MS] intervals.
     *
     * Cancels any in-flight stream from the previous Whisper result first,
     * so stale words never appear after a fresh result starts streaming.
     */
    private fun streamWords(srcText: String, hindiText: String) {
        val words = hindiText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        mainHandler.post {
            cancelWordStream()
            words.forEachIndexed { idx, word ->
                val r = Runnable {
                    pendingWordRunnables.removeFirstOrNull()
                    OverlayService.appendWord(srcText, word, idx == words.lastIndex)
                }
                pendingWordRunnables.add(r)
                mainHandler.postDelayed(r, idx * WORD_INTERVAL_MS)
            }
        }
    }

    private fun cancelWordStream() {
        pendingWordRunnables.forEach { mainHandler.removeCallbacks(it) }
        pendingWordRunnables.clear()
    }

    // ── PCM → WAV ──────────────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val ch = 1; val bps = 16
        val byteRate = SAMPLE_RATE * ch * bps / 8
        val out = ByteArrayOutputStream(pcm.size + 44)
        val dos = DataOutputStream(out)
        dos.writeBytes("RIFF");  dos.writeIntLE(pcm.size + 36); dos.writeBytes("WAVE")
        dos.writeBytes("fmt ");  dos.writeIntLE(16)
        dos.writeShortLE(1);     dos.writeShortLE(ch)
        dos.writeIntLE(SAMPLE_RATE); dos.writeIntLE(byteRate)
        dos.writeShortLE(ch * bps / 8); dos.writeShortLE(bps)
        dos.writeBytes("data");  dos.writeIntLE(pcm.size)
        dos.write(pcm); dos.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff)
        write(v shr 16 and 0xff); write(v shr 24 and 0xff)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v and 0xff); write(v shr 8 and 0xff)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID, "Internal Audio Capture", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating to Hindi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setSilent(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
