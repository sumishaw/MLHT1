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
 * SpeechCaptureService — Reliable translation build
 *
 * ROOT CAUSES FIXED (why "Listening to video audio…" was stuck at 0):
 *
 *  1. connectTimeout WAS 1 000 ms — TOO SHORT.
 *     faster-whisper on a tablet CPU keeps a single-threaded HTTP server.
 *     While it is processing chunk N, chunk N+1's TCP connect() BLOCKS
 *     waiting for the server's accept() loop to come back around. On a
 *     loaded tablet that can easily exceed 1 s, causing every chunk to
 *     throw ConnectException and increment consecutiveFailures until
 *     back-off kicked in — so nothing was ever transcribed.
 *     FIX: connectTimeout = 8 000 ms.
 *
 *  2. readTimeout WAS 5 000 ms — MARGINAL.
 *     faster-whisper tiny/base on a mid-range tablet CPU takes 1–4 s per
 *     1-second chunk. Add HTTP overhead and 5 s is borderline. On a cold
 *     start (first inference loads the model into memory) it can hit 8–12 s.
 *     FIX: readTimeout = 15 000 ms.
 *
 *  3. FIRST_CHUNK = 0.5 s — WHISPER REJECTS SHORT CLIPS.
 *     Whisper's VAD (voice activity detection) requires at least ~1 s of
 *     audio to reliably detect speech. A 0.5 s chunk almost always returns
 *     empty text, burning the first real words and causing the display to
 *     stay at "Listening to video audio…" for the first second.
 *     FIX: Removed FIRST_CHUNK optimisation. All chunks are 1.5 s.
 *     1.5 s gives Whisper more context for accurate language detection
 *     while still being faster than the old 1 s + cold-start queue.
 *
 *  4. JSON FIELD AMBIGUITY.
 *     whisper_server.py (standard faster-whisper wrapper) returns:
 *       {"text": "<transcript>", "language": "<code>", "segments": [...]}
 *     It does NOT return "source_text". Our old code used:
 *       hindiText = json.optString("text", "")
 *       srcText   = json.optString("source_text", hindiText)  ← always == hindiText
 *     So when the server returned Japanese text in "text", we published
 *     Japanese directly as the "translation" and skipped LibreTranslate.
 *     FIX: Use "text" as the raw transcript (always). Check if the
 *     detected language matches the target language; if not, route the
 *     transcript through LibreTranslate. If it already matches (e.g.
 *     English video + English target), publish directly.
 *
 *  5. BACK-OFF TRIGGERED TOO EAGERLY (threshold = 3 failures).
 *     With 1 s connect timeout, 3 consecutive cold-start timeouts triggered
 *     back-off before even one chunk was processed. Back-off then held
 *     chunks for 800 ms–4 s, compounding the startup delay.
 *     FIX: FAILURE_THRESHOLD raised to 6. Back-off max reduced to 3 s
 *     (shorter pauses, faster recovery when whisper comes back).
 *
 *  6. WHISPER_EXECUTOR POOL SIZE = 2 — CORRECT, KEPT.
 *     Two threads is right: one uploads while the other waits for response.
 *     More threads would flood the single-threaded whisper_server.py.
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

        // 1.5 s chunks: enough context for reliable language detection,
        // fast enough for near-real-time display.
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * 1.5).toInt()  // 24 000 samples
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2             // 48 000 bytes

        // 0.25 s overlap for sentence-boundary context
        private const val OVERLAP_BYTES = SAMPLE_RATE / 4 * 2           // 8 000 bytes

        // Larger read buffer reduces rec.read() call frequency
        private const val READ_BUF_BYTES = 8_192

        // Suppress identical consecutive results within this window
        private const val DEDUP_WINDOW_MS = 500L

        // Back-off — only kicks in after 6 consecutive failures
        private const val MAX_BACKOFF_MS    = 3_000L
        private const val BACKOFF_STEP_MS   = 600L
        private const val FAILURE_THRESHOLD = 6

        // Target language codes as returned by faster-whisper
        private val HINDI_CODES   = setOf("hi", "hindi")
        private val ENGLISH_CODES = setOf("en", "english")
    }

    private val mainHandler    = Handler(Looper.getMainLooper())
    private val capturing      = AtomicBoolean(false)
    private var captureThread:   Thread?          = null
    private var audioRecord:     AudioRecord?     = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock:        PowerManager.WakeLock? = null

    // whisperExecutor: 2 threads — chunk N+1 can upload while N is processing
    private val whisperExecutor   = Executors.newFixedThreadPool(2)
    // translateExecutor: separate pool so LibreTranslate never blocks whisper threads
    private val translateExecutor = Executors.newFixedThreadPool(2)

    private var lastPushedText   = ""
    private var lastPushedTimeMs = 0L

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
        // Large hardware buffer: holds 3 full chunks safely
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 3)

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
        Log.d(TAG, "Capture started — chunk=${CHUNK_BYTES}B (${CHUNK_BYTES / (SAMPLE_RATE * 2.0)}s) overlap=${OVERLAP_BYTES}B buf=${bufSize}B")

        captureThread = Thread({
            val chunkBuf = ByteArray(CHUNK_BYTES)
            var chunkPos = 0
            val readBuf  = ByteArray(READ_BUF_BYTES)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                // Capture thread NEVER sleeps — always drains the hardware buffer.
                // Back-off is applied inside scheduleWhisper() via mainHandler.postDelayed.

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
                    val toCopy = minOf(read - src, CHUNK_BYTES - chunkPos)
                    System.arraycopy(readBuf, src, chunkBuf, chunkPos, toCopy)
                    chunkPos += toCopy
                    src      += toCopy

                    if (chunkPos >= CHUNK_BYTES) {
                        val payload = chunkBuf.copyOf(CHUNK_BYTES)
                        // Carry overlap for sentence-boundary context
                        System.arraycopy(chunkBuf, CHUNK_BYTES - OVERLAP_BYTES,
                                         chunkBuf, 0, OVERLAP_BYTES)
                        chunkPos = OVERLAP_BYTES
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
     * Submit chunk to whisperExecutor with optional back-off delay.
     * Back-off runs on mainHandler so the capture thread is never blocked.
     */
    private fun scheduleWhisper(payload: ByteArray) {
        if (whisperExecutor.isShutdown) return
        val failures = consecutiveFailures.get()
        if (failures >= FAILURE_THRESHOLD) {
            val backoffMs = minOf(
                (failures - FAILURE_THRESHOLD + 1).toLong() * BACKOFF_STEP_MS,
                MAX_BACKOFF_MS
            )
            Log.d(TAG, "Back-off ${backoffMs}ms after $failures failures")
            mainHandler.postDelayed({
                if (!whisperExecutor.isShutdown)
                    whisperExecutor.submit { sendToWhisper(payload) }
            }, backoffMs)
        } else {
            whisperExecutor.submit { sendToWhisper(payload) }
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
            // FIX: 8 s connect timeout — allows for whisper_server.py to finish
            //      processing the previous chunk before accepting this one.
            conn.connectTimeout = 8_000
            // FIX: 15 s read timeout — covers model cold-start on first request
            //      (loading faster-whisper model into memory can take 5–10 s).
            conn.readTimeout    = 15_000
            conn.outputStream.use { it.write(wavBytes) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Whisper HTTP $responseCode — skipping chunk")
                consecutiveFailures.incrementAndGet()
                return
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            Log.d(TAG, "Whisper raw response: ${body.take(200)}")

            val json = JSONObject(body)

            // FIX: Robust field handling.
            // Standard faster-whisper server returns {"text": str, "language": str, ...}
            // "text" is always the raw transcript in the detected source language.
            // "source_text" may or may not exist depending on server wrapper version.
            val rawText  = json.optString("text",        "").trim()
            val srcText  = json.optString("source_text", rawText).trim()
            val lang     = json.optString("language",    "").trim().lowercase()
            val conf     = json.optDouble("confidence",   0.0)

            // Use whichever non-empty transcript we have
            val transcript = srcText.ifEmpty { rawText }

            if (transcript.length < 2) {
                Log.d(TAG, "Empty transcript — silence or noise, skipping")
                consecutiveFailures.set(0)  // not a failure, server is working
                return
            }

            consecutiveFailures.set(0)
            Log.d(TAG, "Transcript [$lang ${(conf * 100).toInt()}%]: ${transcript.take(80)}")

            // FIX: Determine if we need translation.
            // If the server already returned translated text in "text" AND
            // "source_text" is different, use "text" directly.
            // Otherwise check if detected language already matches target.
            val targetCode = when (targetLanguage) {
                "hindi"   -> "hi"
                "english" -> "en"
                else      -> "hi"
            }

            val alreadyInTargetLang = when (targetCode) {
                "hi" -> lang in HINDI_CODES
                "en" -> lang in ENGLISH_CODES
                else -> false
            }

            val serverTranslated = rawText.isNotEmpty() &&
                                   srcText.isNotEmpty()  &&
                                   rawText != srcText    // server did translate

            when {
                serverTranslated -> {
                    // Server returned both source_text and translated text
                    Log.d(TAG, "Server-translated: ${rawText.take(60)}")
                    publishResult(srcText, rawText)
                }
                alreadyInTargetLang -> {
                    // Transcript is already in the target language — publish as-is
                    Log.d(TAG, "Already in target lang ($lang): ${transcript.take(60)}")
                    publishResult(transcript, transcript)
                }
                else -> {
                    // Need LibreTranslate — run off the whisper thread
                    val capturedTranscript = transcript
                    val capturedLang       = lang
                    if (!translateExecutor.isShutdown) {
                        translateExecutor.submit {
                            val translated = translateLocally(capturedTranscript, capturedLang)
                            if (translated.length >= 2) {
                                Log.d(TAG, "LibreTranslate [$capturedLang→$targetCode]: ${translated.take(60)}")
                                publishResult(capturedTranscript, translated)
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            consecutiveFailures.incrementAndGet()
            Log.w(TAG, "Whisper error (failure #${consecutiveFailures.get()}): " +
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Publish result ─────────────────────────────────────────────────────────

    private fun publishResult(srcText: String, displayText: String) {
        val now = System.currentTimeMillis()
        if (displayText == lastPushedText && (now - lastPushedTimeMs) < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Dedup suppressed: ${displayText.take(40)}")
            return
        }

        lastPushedText   = displayText
        lastPushedTimeMs = now
        latestOriginal   = srcText
        latestEnglish    = srcText
        latestHindi      = displayText

        mainHandler.post {
            MainActivity.instance?.onTranslation(srcText, displayText, displayText)
            OverlayService.updateText(srcText, displayText)
        }
    }

    // ── Local LibreTranslate ───────────────────────────────────────────────────

    private fun translateLocally(text: String, sourceLang: String): String {
        val src = when (sourceLang.lowercase()) {
            "japanese",   "ja" -> "ja"
            "chinese",    "zh" -> "zh"
            "korean",     "ko" -> "ko"
            "french",     "fr" -> "fr"
            "german",     "de" -> "de"
            "spanish",    "es" -> "es"
            "turkish",    "tr" -> "tr"
            "arabic",     "ar" -> "ar"
            "portuguese", "pt" -> "pt"
            "russian",    "ru" -> "ru"
            "indonesian", "id" -> "id"
            "english",    "en" -> "en"
            else               -> "en"
        }

        val tgt = when (targetLanguage) {
            "hindi"   -> "hi"
            "english" -> "en"
            else      -> "hi"
        }

        if (src == tgt) return text

        return try {
            val body = JSONObject().apply {
                put("q",      text)
                put("source", src)
                put("target", tgt)
                put("format", "text")
            }.toString()

            val conn = URL(LIBRE_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput       = true
            conn.connectTimeout = 3_000
            conn.readTimeout    = 10_000
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
