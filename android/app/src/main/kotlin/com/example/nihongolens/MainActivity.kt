package com.example.nihongolens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MainActivity — Fixed v2
 *
 * KEY FIXES vs previous version:
 *
 *  1. SOCKET-BASED HEALTH CHECK (not HttpURLConnection).
 *     HttpURLConnection routes through Android's network stack.
 *     When Wi-Fi/mobile data disconnects, Android marks ALL network
 *     interfaces unavailable — including loopback — causing
 *     "Network unreachable" even for 127.0.0.1.
 *     A raw Socket(host, port).connect() bypasses this routing table
 *     check and always reaches localhost regardless of internet state.
 *
 *  2. onResume USES silent=true ALWAYS.
 *     The old code called silent=false on every app resume, which meant
 *     every screen-unlock or app-switch fired onModelError if whisper
 *     had even a 3-second hiccup. Now onResume only fires onModelReady
 *     (silent=true), never fires onModelError unless the user explicitly
 *     taps RETRY or the app cold-starts.
 *
 *  3. ONE ACTIVE CHECK AT A TIME (AtomicBoolean guard).
 *     onCreate() started a check AND the Flutter init called isModelReady
 *     concurrently, causing a race where two results arrived out of order.
 *     The checkInProgress guard ensures only one health check runs at a
 *     time; additional requests while one is running are dropped silently.
 *
 *  4. CAPTURE-AWARE ERROR SUPPRESSION.
 *     If SpeechCaptureService.isRunning == true, onModelError is never
 *     sent — the capture service already handles retries per-chunk and
 *     the user does not need to see a red error card while audio is
 *     actively being transcribed.
 *
 *  5. POLL INTERVAL INCREASED to 30 s (was 15 s) to reduce UI flicker.
 *     Silent polls never send onModelError, so the only downside of a
 *     longer interval is a slight delay before the "ready" green card
 *     appears after the user starts whisper_server.py.
 */
class MainActivity : FlutterActivity() {

    companion object {
        @Volatile var instance: MainActivity? = null

        private const val REQ_MEDIA_PROJECTION = 200
        private const val REQ_AUDIO_PERMISSION = 100
        private const val TAG                  = "MainActivity"

        private const val WHISPER_HOST         = "127.0.0.1"
        private const val WHISPER_PORT         = 8765

        // Longer poll interval — silent polls never fire onModelError anyway
        private const val HEALTH_POLL_INTERVAL_MS = 30_000L

        // Socket connect timeout — short so the UI responds quickly
        private const val SOCKET_TIMEOUT_MS = 2_000
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    @Volatile private var pendingProjectionResult: MethodChannel.Result? = null

    // Single-thread executor — health checks never pile up
    private val healthExecutor = Executors.newSingleThreadExecutor()

    // Guard: only one health check in flight at a time
    private val checkInProgress = AtomicBoolean(false)

    // Main-thread handler for periodic polling
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthPollRunnable: Runnable = object : Runnable {
        override fun run() {
            // Always silent during polling — don't spam the error card
            checkAndNotifyWhisperReady(silent = true)
            mainHandler.postDelayed(this, HEALTH_POLL_INTERVAL_MS)
        }
    }

    // ── Flutter method channel ─────────────────────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {

                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                "hasAudioPermission" ->
                    result.success(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                    )

                "requestAudioPermission" ->
                    requestAudioThenProjection(result)

                // Accessibility not required for internal-audio capture
                "checkAccessibilityEnabled" -> result.success(true)
                "openAccessibilitySettings" -> result.success(true)

                // ── Whisper server readiness ─────────────────────────────────

                "isModelReady" -> checkWhisperReady { ready ->
                    runOnUiThread { result.success(ready) }
                }

                "getModelStatus" -> checkWhisperReady { ready ->
                    runOnUiThread {
                        result.success(if (ready) "ready" else "not_downloaded")
                    }
                }

                /**
                 * Called on RETRY tap. Always non-silent so the user sees
                 * the result of their explicit action. The AtomicBoolean guard
                 * prevents duplicate concurrent checks even if tapped rapidly.
                 */
                "startModelDownload" -> {
                    result.success(true)
                    checkAndNotifyWhisperReady(silent = false)
                }

                // ── Overlay ──────────────────────────────────────────────────

                "startOverlay" -> {
                    val i = Intent(this, OverlayService::class.java)
                    startForegroundServiceCompat(i)
                    result.success(true)
                }

                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(true)
                }

                // ── Speech capture ────────────────────────────────────────────

                "startSpeechCapture" ->
                    requestAudioThenProjection(result)

                "stopSpeechCapture" -> {
                    stopService(Intent(this, SpeechCaptureService::class.java))
                    result.success(true)
                }

                "isSpeechCaptureRunning" ->
                    result.success(SpeechCaptureService.isRunning)

                "setTargetLanguage" -> {
                    val lang = call.argument<String>("language") ?: "hindi"
                    SpeechCaptureService.targetLanguage = lang
                    result.success(true)
                }

                "getLatestTranslation" ->
                    result.success(
                        mapOf(
                            "original" to SpeechCaptureService.latestOriginal,
                            "english"  to SpeechCaptureService.latestEnglish,
                            "hindi"    to SpeechCaptureService.latestHindi
                        )
                    )

                else -> result.notImplemented()
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Non-silent on cold start so the user sees the initial server state
        checkAndNotifyWhisperReady(silent = false)
        // Start periodic polling (always silent — only notifies on recovery)
        mainHandler.postDelayed(healthPollRunnable, HEALTH_POLL_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // FIX: Always silent=true on resume.
        // We only send onModelReady (green card) if whisper came back up.
        // We NEVER send onModelError here — avoids flicker on every
        // screen-unlock / app-switch when internet is disconnected.
        checkAndNotifyWhisperReady(silent = true)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(healthPollRunnable)
        pendingProjectionResult?.success(false)
        pendingProjectionResult = null
        healthExecutor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    // ── Whisper server health checks ───────────────────────────────────────────

    /**
     * Check if whisper_server.py is accepting connections on 127.0.0.1:8765
     * using a raw TCP Socket — NOT HttpURLConnection.
     *
     * WHY SOCKET: When the device has no internet connection, Android's
     * ConnectivityManager marks the default network as unavailable. Any call
     * that goes through the Android network stack (HttpURLConnection, OkHttp,
     * Volley, etc.) immediately throws "Network unreachable" for ALL
     * destinations — including 127.0.0.1.
     * A raw java.net.Socket bypasses the network-availability check entirely
     * and connects directly to the loopback interface, which is always up as
     * long as the device is powered on.
     *
     * [onResult] is called on the healthExecutor thread with true/false.
     */
    private fun checkWhisperReady(onResult: (Boolean) -> Unit) {
        healthExecutor.submit {
            val ready = try {
                Socket().use { sock ->
                    sock.connect(
                        java.net.InetSocketAddress(WHISPER_HOST, WHISPER_PORT),
                        SOCKET_TIMEOUT_MS
                    )
                    true   // connection accepted — server is up
                }
            } catch (_: Exception) {
                false
            }
            onResult(ready)
        }
    }

    /**
     * Check whisper readiness and broadcast the result to the Flutter UI.
     *
     * [silent] = true  → only fire onModelReady (server came back up).
     *                     Never fires onModelError — used for background
     *                     polls and onResume to avoid spamming the error card.
     * [silent] = false → fire onModelReady OR onModelError — used only on
     *                     cold start and explicit RETRY taps.
     *
     * CAPTURE-AWARE: if SpeechCaptureService.isRunning is true we suppress
     * onModelError entirely even on non-silent checks, because:
     *  a) the capture service handles per-chunk retries autonomously, and
     *  b) showing the red error card while captions are flowing confuses users.
     */
    private fun checkAndNotifyWhisperReady(silent: Boolean) {
        // Drop duplicate concurrent checks
        if (!checkInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Health check already in progress — skipping")
            return
        }

        checkWhisperReady { ready ->
            checkInProgress.set(false)
            runOnUiThread {
                if (ready) {
                    Log.d(TAG, "whisper_server.py is ready")
                    methodChannel?.invokeMethod("onModelReady", null)
                } else if (!silent && !SpeechCaptureService.isRunning) {
                    // Only show the error card if:
                    //  1. This is an explicit check (not a background poll), AND
                    //  2. Capture is NOT currently running (user doesn't need to see it)
                    Log.w(TAG, "whisper_server.py not reachable on port $WHISPER_PORT")
                    methodChannel?.invokeMethod(
                        "onModelError",
                        mapOf(
                            "message" to
                                "Whisper server not running.\n" +
                                "Start it with:\n  python3 whisper_server.py\n" +
                                "Then tap RETRY."
                        )
                    )
                }
                // All other cases (silent, or capture running): no UI change
            }
        }
    }

    // ── Permission + projection flow ───────────────────────────────────────────

    private fun requestAudioThenProjection(result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) {
            result.success(false); return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaProjection(result)
        } else {
            deliverPendingFailure()
            pendingProjectionResult = result
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO_PERMISSION
            )
        }
    }

    private fun requestMediaProjection(result: MethodChannel.Result) {
        deliverPendingFailure()
        pendingProjectionResult = result
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "createScreenCaptureIntent failed: ${e.message}")
            pendingProjectionResult = null
            result.success(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            val pending = pendingProjectionResult
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pending != null) {
                    pendingProjectionResult = null
                    requestMediaProjection(pending)
                }
            } else {
                pendingProjectionResult = null
                pending?.success(false)
            }
        }
    }

    @Deprecated("Required for API compatibility below 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_MEDIA_PROJECTION) {
            val pending = pendingProjectionResult
            pendingProjectionResult = null

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection granted — starting SpeechCaptureService")
                val i = Intent(this, SpeechCaptureService::class.java).apply {
                    putExtra(SpeechCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(SpeechCaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundServiceCompat(i)
                pending?.success(true)
            } else {
                Log.w(TAG, "MediaProjection denied (resultCode=$resultCode)")
                pending?.success(false)
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun deliverPendingFailure() {
        val stale = pendingProjectionResult
        if (stale != null) {
            pendingProjectionResult = null
            try { stale.success(false) } catch (_: Exception) {}
        }
    }

    /** Called from SpeechCaptureService to push a translation to the Flutter UI. */
    fun onTranslation(original: String, english: String, hindi: String) {
        runOnUiThread {
            methodChannel?.invokeMethod(
                "onTranslation",
                mapOf("original" to original, "english" to english, "hindi" to hindi)
            )
        }
    }
}
