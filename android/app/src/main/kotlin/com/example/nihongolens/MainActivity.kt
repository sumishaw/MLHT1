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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * MainActivity  —  Fixed version
 *
 * KEY FIXES:
 *  1. Whisper health-check failure NO LONGER stops or locks out capture.
 *     The UI shows the error card but capture can still be started/continued.
 *  2. Periodic background health-check (every 15 s) so the UI stays in sync
 *     with the server without user interaction.
 *  3. "startSpeechCapture" no longer requires modelState == ready in Kotlin
 *     (the Flutter guard was already the only gate; now it also won't block
 *     on a transient health-check failure if capture was previously working).
 *  4. Health check is fully offline-safe — only touches 127.0.0.1.
 */
class MainActivity : FlutterActivity() {

    companion object {
        @Volatile var instance: MainActivity? = null

        private const val REQ_MEDIA_PROJECTION  = 200
        private const val REQ_AUDIO_PERMISSION  = 100
        private const val TAG                   = "MainActivity"

        private const val WHISPER_HEALTH_URL    = "http://127.0.0.1:8765/ready"

        // How often (ms) we silently re-check whisper while app is foregrounded
        private const val HEALTH_POLL_INTERVAL_MS = 15_000L
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    @Volatile private var pendingProjectionResult: MethodChannel.Result? = null

    // Single-thread executor — health checks never pile up
    private val healthExecutor = Executors.newSingleThreadExecutor()

    // Main-thread handler for periodic polling
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthPollRunnable: Runnable = object : Runnable {
        override fun run() {
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
                 * Flutter calls startModelDownload on first launch or RETRY tap.
                 * We trigger a health check and fire the appropriate callback.
                 * IMPORTANT: A failure here does NOT prevent capture — it only
                 * updates the UI status card.
                 */
                "startModelDownload" -> {
                    result.success(true)          // acknowledge immediately
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

                /**
                 * FIX: We no longer block startSpeechCapture on whisper health.
                 * The capture service connects to whisper per-chunk; if whisper
                 * is temporarily unreachable for one chunk it retries on the next.
                 * Capture is NEVER stopped due to a health-check failure.
                 */
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
        // Initial check on launch
        checkAndNotifyWhisperReady(silent = false)
        // Start periodic polling
        mainHandler.postDelayed(healthPollRunnable, HEALTH_POLL_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // Re-check immediately when coming back to the app (e.g. after starting whisper)
        checkAndNotifyWhisperReady(silent = false)
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
     * Asynchronously check if whisper_server.py is running on 127.0.0.1.
     * Fully offline-safe — no internet access needed.
     * [onResult] is called on the executor thread with true/false.
     */
    private fun checkWhisperReady(onResult: (Boolean) -> Unit) {
        healthExecutor.submit {
            val ready = try {
                val conn = URL(WHISPER_HEALTH_URL).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) {
                false
            }
            onResult(ready)
        }
    }

    /**
     * Check whisper readiness and broadcast the result to Flutter UI.
     *
     * [silent] = true  → only fire onModelReady (don't spam onModelError
     *                     every 15 s if whisper hasn't started yet — the
     *                     user already saw the error card on launch).
     * [silent] = false → fire both onModelReady and onModelError (used on
     *                     launch, onResume, and RETRY taps).
     */
    private fun checkAndNotifyWhisperReady(silent: Boolean) {
        checkWhisperReady { ready ->
            runOnUiThread {
                if (ready) {
                    Log.d(TAG, "whisper_server.py is ready")
                    methodChannel?.invokeMethod("onModelReady", null)
                } else if (!silent) {
                    Log.w(TAG, "whisper_server.py not reachable on port 8765")
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
                // If silent && !ready: do nothing — don't change the UI state
                // so the user isn't confused by a flicker back to "error" state
                // if whisper briefly hiccups while capture is running fine.
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
