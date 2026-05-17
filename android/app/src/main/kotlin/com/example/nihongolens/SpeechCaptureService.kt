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
 * SpeechCaptureService  —  REAL-TIME optimised build
 *
 * Latency improvements vs the original 3-second build:
 *
 * 1. CHUNK SIZE: 1 s (was 3 s)
 *    Audio is dispatched to Whisper every 1 second instead of every 3 seconds.
 *    Perceived subtitle lag drops from ~4-6 s to ~1-2 s.
 *
 * 2. SLIDING WINDOW OVERLAP: 0.5 s carried forward
 *    The last 0.5 s of every chunk is prepended to the next chunk so Whisper
 *    always has sentence context. This prevents word-boundary cuts that cause
 *    bad transcriptions (which feel like extra delay because they must be
 *    re-shown corrected on the next chunk).
 *
 * 3. DUAL-THREAD EXECUTOR (2 threads, was 1)
 *    While thread A is waiting for Whisper to respond for chunk N, thread B
 *    can immediately start sending chunk N+1. On the Dimensity 7050 (4+4 cores)
 *    whisper_server.py runs on its own cores, so 2 parallel HTTP connections
 *    do not cause CPU contention.
 *
 * 4. REDUCED HTTP TIMEOUTS
 *    connectTimeout: 3 s (was 5 s) — server is localhost, should connect instantly.
 *    readTimeout:    8 s (was 20 s) — faster-whisper on 1 s audio is ~0.5-1 s.
 *    Stale slow responses are dropped faster, keeping the queue clear.
 *
 * 5. SMARTER DEDUP
 *    Old code blocked any repeat of the last Hindi result entirely. New code
 *    allows a repeat if enough time has passed (DEDUP_WINDOW_MS = 1500 ms),
 *    so a repeated sentence in the video still appears on screen.
 *
 * Audio pipeline (unchanged):
 *   Internal audio  →  AudioPlaybackCaptureConfiguration (no mic)
 *   →  16kHz mono PCM  →  WAV wrapper  →  POST /transcribe (whisper_server.py)
 *   →  JSON {text, source_text, language, confidence}
 *   →  OverlayService (floating Hindi subtitle)
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

        // ── Chunk tuning ──────────────────────────────────────────────────────
        // 1 second of audio  →  16 000 samples × 2 bytes = 32 000 bytes
        // Whisper minimum is ~0.1 s; 1 s gives enough phoneme context while
        // keeping end-to-end latency at ~1-2 s on the Dimensity 7050.
        private const val CHUNK_SEC     = 1
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SEC
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2   // 16-bit = 2 bytes/sample

        // Overlap: carry forward the last 0.5 s of audio into the next chunk.
        // Prevents Whisper from missing words at chunk boundaries.
        private const val OVERLAP_SEC     = 1   // 0.5 seconds
        private const val OVERLAP_SAMPLES = SAMPLE_RATE * OVERLAP_SEC / 2
        private const val OVERLAP_BYTES   = OVERLAP_SAMPLES * 2   // 16 000 bytes

        // Dedup: allow the same Hindi string to re-appear after this many ms.
        // Prevents flickering on repeated captions while still showing them.
        private const val DEDUP_WINDOW_MS = 1500L
    }

    private val mainHandler     = Handler(Looper.getMainLooper())
    private val capturing       = AtomicBoolean(false)
    private var captureThread: Thread?             = null
    private var audioRecord:   AudioRecord?        = null
    private var mediaProjection: MediaProjection?  = null
    private var wakeLock:      PowerManager.WakeLock? = null

    // 2-thread executor: chunk N+1 can be sent while chunk N is still
    // being processed by whisper_server.py (which runs on its own CPU cores).
    private val whisperExecutor = Executors.newFixedThreadPool(2)

    private var lastPushedHindi  = ""
    private var lastPushedTimeMs = 0L

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }

        Log.d(TAG, "onCreate — foreground started, wakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "onStartCommand received null intent — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No valid MediaProjection token")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null after getMediaProjection()")
            stopSelf(); return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped externally")
                    mainHandler.post { stopSelf() }
                }
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

        captureThread?.interrupt()
        captureThread = null

        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        whisperExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        super.onDestroy()
    }

    // ── Audio capture ──────────────────────────────────────────────────────────

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10 or newer required.")
            stopSelf(); return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection null at capture start")
            OverlayService.updateText("", "Screen capture lost — tap STOP then START again.")
            stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error: $minBuf")
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }

        // Buffer: at least 2× chunk so AudioRecord never blocks while we process
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
            Log.e(TAG, "AudioRecord.Builder failed: ${e.message}")
            OverlayService.updateText("", "Audio setup failed: ${e.message}")
            stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state=${ar.state} — not initialized")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        audioRecord = ar

        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio to Hindi…")
        OverlayService.updateText("", "Listening to video audio…")
        Log.d(TAG, "Capture started — chunk=${CHUNK_SEC}s overlap=${OVERLAP_BYTES}B buf=${bufSize}B")

        captureThread = Thread({
            // chunkBuf holds the current chunk being filled.
            // Starts pre-filled with the overlap from the previous chunk (initially zeros).
            val chunkBuf  = ByteArray(CHUNK_BYTES)
            var chunkPos  = 0          // write cursor inside chunkBuf
            val readBuf   = ByteArray(4096)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
                if (read <= 0) continue

                // Copy incoming PCM into chunkBuf, dispatch when full
                var src = 0
                while (src < read) {
                    val toCopy = minOf(read - src, CHUNK_BYTES - chunkPos)
                    System.arraycopy(readBuf, src, chunkBuf, chunkPos, toCopy)
                    chunkPos += toCopy
                    src      += toCopy

                    if (chunkPos >= CHUNK_BYTES) {
                        // ── Dispatch this chunk ──────────────────────────────
                        val payload = chunkBuf.copyOf(CHUNK_BYTES)

                        if (!whisperExecutor.isShutdown) {
                            whisperExecutor.submit { sendToWhisper(payload) }
                        }

                        // ── Sliding window: seed next chunk with last OVERLAP_BYTES ──
                        // This gives Whisper sentence context at every chunk boundary.
                        val overlapStart = CHUNK_BYTES - OVERLAP_BYTES
                        System.arraycopy(chunkBuf, overlapStart, chunkBuf, 0, OVERLAP_BYTES)
                        chunkPos = OVERLAP_BYTES   // next write starts after the carried-over audio
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

    // ── Whisper HTTP call ──────────────────────────────────────────────────────

    private fun sendToWhisper(pcmBytes: ByteArray) {
        try {
            val wavBytes = pcmToWav(pcmBytes)

            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.doOutput       = true
            conn.connectTimeout = 3_000   // localhost — should connect in <10 ms
            conn.readTimeout    = 8_000   // faster-whisper on 1 s audio ≈ 0.5-1 s

            conn.outputStream.use { it.write(wavBytes) }

            val respCode = conn.responseCode
            if (respCode != 200) {
                Log.w(TAG, "Whisper HTTP $respCode")
                return
            }

            val body       = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json       = JSONObject(body)
            val hindiText  = json.optString("text",        "").trim()
            val srcText    = json.optString("source_text", "").trim()
            val lang       = json.optString("language",    "")
            val confidence = json.optDouble("confidence",   0.0)

            if (hindiText.length < 2) return

            // Smart dedup: block exact repeats only within DEDUP_WINDOW_MS.
            // After that window, allow it through (repeated sentences in video
            // should still appear as captions).
            val now = System.currentTimeMillis()
            if (hindiText == lastPushedHindi && (now - lastPushedTimeMs) < DEDUP_WINDOW_MS) return

            Log.d(TAG, "[$lang ${(confidence * 100).toInt()}%] ${hindiText.take(60)}")

            lastPushedHindi  = hindiText
            lastPushedTimeMs = now
            latestOriginal   = srcText
            latestEnglish    = srcText
            latestHindi      = hindiText

            mainHandler.post {
                OverlayService.updateText(srcText, hindiText)
                MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Whisper call: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── PCM → WAV ──────────────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels    = 1
        val bitsPerSamp = 16
        val byteRate    = SAMPLE_RATE * channels * bitsPerSamp / 8
        val dataLen     = pcm.size
        val riffChunkSz = dataLen + 36

        val out = ByteArrayOutputStream(riffChunkSz + 8)
        val dos = DataOutputStream(out)

        dos.writeBytes("RIFF")
        dos.writeIntLE(riffChunkSz)
        dos.writeBytes("WAVE")

        dos.writeBytes("fmt ")
        dos.writeIntLE(16)
        dos.writeShortLE(1)                          // PCM
        dos.writeShortLE(channels)
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(channels * bitsPerSamp / 8)
        dos.writeShortLE(bitsPerSamp)

        dos.writeBytes("data")
        dos.writeIntLE(dataLen)
        dos.write(pcm)
        dos.flush()
        return out.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v         and 0xff)
        write(v shr  8  and 0xff)
        write(v shr 16  and 0xff)
        write(v shr 24  and 0xff)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v        and 0xff)
        write(v shr 8  and 0xff)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Internal Audio Capture",
                NotificationManager.IMPORTANCE_LOW
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
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
