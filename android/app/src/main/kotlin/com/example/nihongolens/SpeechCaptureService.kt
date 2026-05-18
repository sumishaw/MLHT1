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
import java.util.concurrent.atomic.AtomicInteger

/**
 * SpeechCaptureService — Fast-translation build
 *
 * LATENCY FIXES vs previous build:
 *
 *  1. FIRST CHUNK IS 0.5 s (not 1 s).
 *     The very first chunk dispatched to Whisper is only 0.5 s of audio
 *     so the first translation appears in ~0.5 s + Whisper inference time
 *     instead of ~1 s + inference time.  After the first chunk, all
 *     subsequent chunks are the standard 1 s for accuracy.
 *
 *  2. NO OVERLAP ON FIRST CHUNK.
 *     The old code pre-filled chunkPos = OVERLAP_BYTES on startup, which
 *     meant the first chunk was already half-full of silence before any
 *     real audio arrived, adding another ~0.5 s of dead time.
 *
 *  3. LIBRETRANSLATE RUNS IN PARALLEL, NOT IN SERIES.
 *     Previously sendToWhisper() called translateLocally() synchronously
 *     inside itself — one thread was blocked on Whisper HTTP then blocked
 *     again on LibreTranslate HTTP before it could pick up the next chunk.
 *     Now sendToWhisper() submits the LibreTranslate call to a *separate*
 *     dedicated translateExecutor thread, so the whisperExecutor thread is
 *     free immediately after the Whisper response arrives.
 *
 *  4. LARGER READ BUFFER (8 192 B → ~256 ms per read).
 *     Halves the number of rec.read() iterations needed to fill a chunk,
 *     reducing lock contention on the AudioRecord internal buffer.
 *
 *  5. WORD STREAMING REPLACED WITH INSTANT FULL-TEXT DISPLAY.
 *     The old word-by-word stream added up to 1.8 s of fake delay for a
 *     10-word sentence — while the *next* chunk's result was already
 *     waiting.  Now the full translated text is shown immediately.
 *     The overlay word-append API is still called once with the full text.
 *
 *  6. DEDUP WINDOW REDUCED: 1 200 ms → 400 ms.
 *     The old window suppressed a valid new result for over a second.
 *     400 ms is enough to catch true duplicates from the overlap window.
 *
 *  7. BACK-OFF APPLIED ONLY TO WHISPER EXECUTOR, NOT CAPTURE THREAD.
 *     Previously Thread.sleep() was called in the capture thread, which
 *     starved the AudioRecord hardware buffer and caused overflow noise.
 *     Now back-off is implemented as a delayed re-submission inside the
 *     whisper executor — the capture thread always runs at full speed.
 *
 *  8. CONNECT TIMEOUTS TIGHTENED.
 *     Whisper: connect 1 s / read 5 s  (was 3 s / 8 s)
 *     LibreTranslate: connect 1 s / read 5 s  (was 4 s / 8 s)
 *     These servers are on loopback — if they don't answer in 1 s they
 *     are not running; waiting longer just blocks the thread.
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
        private const val LIBRE_URL   = "http://127.0.0.1:5000/translate"

        // Standard chunk = 1 s of audio
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 1      // 16 000 samples
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2    // 32 000 bytes

        // FAST-START: first chunk is only 0.5 s so the first result
        // appears sooner; subsequent chunks are the full 1 s.
        private const val FIRST_CHUNK_BYTES = CHUNK_BYTES / 2  // 16 000 bytes

        // Overlap carried into each chunk for sentence-boundary context
        private const val OVERLAP_BYTES = SAMPLE_RATE / 4 * 2  // 0.25 s = 8 000 bytes
                                                                 // (was 0.5 s — too long)

        // Larger read buffer: fewer rec.read() calls per chunk
        private const val READ_BUF_BYTES = 8_192

        // Suppress identical consecutive results within this window
        private const val DEDUP_WINDOW_MS = 400L   // was 1 200 ms

        // Back-off parameters (applied inside whisperExecutor, not capture thread)
        private const val MAX_BACKOFF_MS    = 4_000L
        private const val BACKOFF_STEP_MS   = 800L
        private const val FAILURE_THRESHOLD = 3
    }

    private val mainHandler    = Handler(Looper.getMainLooper())
    private val capturing      = AtomicBoolean(false)
    private var captureThread:   Thread?          = null
    private var audioRecord:     AudioRecord?     = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock:        PowerManager.WakeLock? = null

    // whisperExecutor: 2 threads — chunk N+1 uploads while Whisper processes N
    private val whisperExecutor   = Executors.newFixedThreadPool(2)
    // translateExecutor: separate pool so LibreTranslate never blocks whisper threads
    private val translateExecutor = Executors.newFixedThreadPool(2)

    private var lastPushedText   = ""
    private var lastPushedTimeMs = 0L

    // Consecutive failure counter
    private val consecutiveFailures = AtomicInteger(0)

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
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }
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
        translateExecutor.shutdownNow()
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
        // Buffer must fit at least 2 full chunks without overflow
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
        updateNotification("Translating video audio…")
        OverlayService.updateText("", "Listening…")
        Log.d(TAG, "Capture started — firstChunk=${FIRST_CHUNK_BYTES}B stdChunk=${CHUNK_BYTES}B overlap=${OVERLAP_BYTES}B buf=${bufSize}B")

        captureThread = Thread({
            val chunkBuf = ByteArray(CHUNK_BYTES)
            var chunkPos = 0
            val readBuf  = ByteArray(READ_BUF_BYTES)

            // FIX: Start with chunkPos = 0, no pre-filled overlap.
            // The first dispatch threshold is FIRST_CHUNK_BYTES (0.5 s)
            // so the first translation fires after only ~0.5 s of audio.
            var isFirstChunk = true

            while (capturing.get() && !Thread.currentThread().isInterrupted) {

                // NOTE: Back-off is now inside whisperExecutor (see scheduleWhisper).
                // The capture thread NEVER sleeps — it always drains the
                // AudioRecord hardware buffer to prevent overflow.

                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)

                if (read == AudioRecord.ERROR_INVALID_OPERATION
                    || read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "AudioRecord.read error: $read"); break
                }
                if (read <= 0) continue

                var src = 0
                while (src < read) {
                    // Dispatch threshold: FIRST_CHUNK_BYTES on first chunk, CHUNK_BYTES after
                    val threshold = if (isFirstChunk) FIRST_CHUNK_BYTES else CHUNK_BYTES
                    val toCopy    = minOf(read - src, threshold - chunkPos)
                    System.arraycopy(readBuf, src, chunkBuf, chunkPos, toCopy)
                    chunkPos += toCopy
                    src      += toCopy

                    if (chunkPos >= threshold) {
                        val payload = chunkBuf.copyOf(chunkPos)

                        // Carry overlap into next chunk for sentence-boundary context
                        if (chunkPos > OVERLAP_BYTES) {
                            System.arraycopy(
                                chunkBuf, chunkPos - OVERLAP_BYTES,
                                chunkBuf, 0, OVERLAP_BYTES
                            )
                            chunkPos = OVERLAP_BYTES
                        } else {
                            chunkPos = 0
                        }

                        isFirstChunk = false
                        scheduleWhisper(payload)
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

    /**
     * Submit a chunk to the whisperExecutor.
     * Back-off (if any) happens here — NOT in the capture thread.
     */
    private fun scheduleWhisper(payload: ByteArray) {
        if (whisperExecutor.isShutdown) return
        val failures = consecutiveFailures.get()
        if (failures >= FAILURE_THRESHOLD) {
            val backoffMs = minOf(
                (failures - FAILURE_THRESHOLD + 1).toLong() * BACKOFF_STEP_MS,
                MAX_BACKOFF_MS
            )
            // Post with delay so the capture thread is never stalled
            mainHandler.postDelayed({
                if (!whisperExecutor.isShutdown)
                    whisperExecutor.submit { sendToWhisper(payload) }
            }, backoffMs)
        } else {
            whisperExecutor.submit { sendToWhisper(payload) }
        }
    }

    // ── Whisper HTTP ───────────────────────────────────────────────────────────

    /**
     * POST one WAV chunk to whisper_server.py and handle the response.
     *
     * PARALLELISM: once we have the Whisper JSON response, if LibreTranslate
     * is needed we submit it to translateExecutor — the whisper thread is
     * freed immediately to pick up the next chunk.
     */
    private fun sendToWhisper(pcmBytes: ByteArray) {
        try {
            val wavBytes = pcmToWav(pcmBytes)
            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.doOutput       = true
            conn.connectTimeout = 1_000   // loopback — if no answer in 1 s, not running
            conn.readTimeout    = 5_000   // Whisper inference should finish in 5 s
            conn.outputStream.use { it.write(wavBytes) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "Whisper HTTP ${conn.responseCode} — skipping chunk")
                consecutiveFailures.incrementAndGet()
                return
            }

            val body  = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json  = JSONObject(body)

            val hindiText = json.optString("text",        "").trim()
            val srcText   = json.optString("source_text", hindiText).trim()
            val lang      = json.optString("language",    "")
            val conf      = json.optDouble("confidence",   0.0)

            consecutiveFailures.set(0)

            when {
                hindiText.length >= 2 -> {
                    // Whisper server already translated — publish immediately
                    Log.d(TAG, "[$lang ${(conf * 100).toInt()}%] ${ hindiText.take(60)}")
                    publishResult(srcText, hindiText)
                }
                srcText.length >= 2 -> {
                    // Need LibreTranslate — run it on the translate executor
                    // so this whisper thread is freed right now
                    if (!translateExecutor.isShutdown) {
                        translateExecutor.submit {
                            val translated = translateLocally(srcText, lang)
                            if (translated.length >= 2) {
                                Log.d(TAG, "[libre $lang] ${translated.take(60)}")
                                publishResult(srcText, translated)
                            }
                        }
                    }
                }
                else -> { /* silence / noise — discard */ }
            }

        } catch (e: Exception) {
            consecutiveFailures.incrementAndGet()
            Log.w(TAG, "Whisper error (failure #${consecutiveFailures.get()}): " +
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Publish a translation result to the UI and overlay.
     * Called from either the whisper thread or the translate thread.
     */
    private fun publishResult(srcText: String, displayText: String) {
        val now = System.currentTimeMillis()
        if (displayText == lastPushedText && (now - lastPushedTimeMs) < DEDUP_WINDOW_MS) return

        lastPushedText   = displayText
        lastPushedTimeMs = now
        latestOriginal   = srcText
        latestEnglish    = srcText
        latestHindi      = displayText

        // Post full text to UI immediately — no word-by-word delay
        mainHandler.post {
            MainActivity.instance?.onTranslation(srcText, displayText, displayText)
            // Show full text in overlay at once
            OverlayService.updateText(srcText, displayText)
        }
    }

    // ── Local LibreTranslate (offline) ─────────────────────────────────────────

    private fun translateLocally(text: String, sourceLang: String): String {
        val src = when (sourceLang.lowercase()) {
            "japanese",  "ja"  -> "ja"
            "chinese",   "zh"  -> "zh"
            "korean",    "ko"  -> "ko"
            "french",    "fr"  -> "fr"
            "german",    "de"  -> "de"
            "spanish",   "es"  -> "es"
            "turkish",   "tr"  -> "tr"
            "arabic",    "ar"  -> "ar"
            "portuguese","pt"  -> "pt"
            "russian",   "ru"  -> "ru"
            "indonesian","id"  -> "id"
            "english",   "en"  -> "en"
            else               -> "en"
        }

        val tgtLang = when (targetLanguage) {
            "hindi"   -> "hi"
            "english" -> "en"
            else      -> "hi"
        }

        if (src == tgtLang) return text

        return try {
            val body = JSONObject().apply {
                put("q",      text)
                put("source", src)
                put("target", tgtLang)
                put("format", "text")
            }.toString()

            val conn = URL(LIBRE_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput       = true
            conn.connectTimeout = 1_000   // loopback — fast fail
            conn.readTimeout    = 5_000
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "LibreTranslate HTTP ${conn.responseCode} — using source text")
                return text
            }

            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("translatedText", text).trim().ifEmpty { text }

        } catch (e: Exception) {
            Log.w(TAG, "LibreTranslate error: ${e.message} — using source text")
            text
        }
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
             .also {
                 getSystemService(NotificationManager::class.java)
                     .createNotificationChannel(it)
             }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setSilent(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
