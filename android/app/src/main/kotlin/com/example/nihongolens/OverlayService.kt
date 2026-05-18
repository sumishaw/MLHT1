package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService — Fast instant-display build
 *
 * WHAT CHANGED vs the word-streaming build:
 *
 *  1. INSTANT FULL-TEXT DISPLAY.
 *     Previously updateText() split the sentence into individual words and
 *     fed them through appendWordToLines() one at a time. Each word caused
 *     a TextView update, and when both lines were full a 4-second READ_PAUSE
 *     froze all output. A 10-word sentence could take 4+ seconds to fully
 *     appear on the overlay.
 *
 *     Now updateText() shows the translated text IMMEDIATELY as two balanced
 *     lines with zero delay. No word queue, no read pause, no per-word loop.
 *
 *  2. SMART TWO-LINE SPLIT.
 *     The text is split at the nearest space to the midpoint, so both lines
 *     are roughly equal length — more readable than filling line 1 to max
 *     chars and dumping the rest on line 2.
 *     If the text fits on one line (≤ MAX_CHARS_PER_LINE) it stays on line 1
 *     with line 2 empty.
 *
 *  3. SILENCE FADE-OUT KEPT.
 *     After CLEAR_AFTER_MS (6 s) of no new text the overlay fades out
 *     gracefully. This is the only timer that remains.
 *
 *  4. DEAD CODE REMOVED.
 *     appendWord(), onWordArrived(), enqueueWords(), appendWordToLines(),
 *     startReadPause(), drainWordQueue(), wordQueue, activeLine, isPaused
 *     are all gone. The companion object still has appendWord() as a no-op
 *     stub so any callers compile without changes.
 *
 * Layout (unchanged):
 *   Two TextViews stacked vertically, full screen width, anchored near the
 *   bottom, draggable. White bold text with black shadow.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        /**
         * Primary entry point — called by SpeechCaptureService with the
         * full translated sentence. Shows text on the overlay immediately.
         */
        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            instance?.showTextNow(hindi)
        }

        /**
         * Legacy stub — kept so SpeechCaptureService compiles unchanged
         * if it still calls appendWord(). Does nothing; updateText() is
         * the only path used now.
         */
        @Suppress("UNUSED_PARAMETER")
        fun appendWord(original: String, word: String, isLastWord: Boolean) {
            // no-op — word streaming removed; use updateText() instead
        }

        @Volatile var instance: OverlayService? = null
    }

    // ── Tuning ──────────────────────────────────────────────────────────────

    // Characters that fit comfortably on one line at SP=22 on a 12″ tablet.
    // Increase if text is too short per line; decrease if it overflows edges.
    private val MAX_CHARS_PER_LINE = 38

    // Seconds of silence before the overlay fades out
    private val CLEAR_AFTER_MS = 6_000L

    // ── State ────────────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var tv1:           TextView?                   = null
    private var tv2:           TextView?                   = null
    private var params:        WindowManager.LayoutParams? = null
    @Volatile private var viewAdded = false
    @Volatile private var running   = false

    private var silenceClearRunnable: Runnable? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        running  = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running  = false
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Public display entry point ────────────────────────────────────────────

    /**
     * Display [text] on the overlay immediately, split across two lines.
     * Must be called on the main thread — SpeechCaptureService already
     * posts via mainHandler before calling updateText().
     *
     * If called from a background thread, use:
     *   mainHandler.post { showTextNow(text) }
     */
    fun showTextNow(text: String) {
        if (!running) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val (l1, l2) = splitToTwoLines(trimmed)

        // Make both lines fully opaque and set text immediately — no animation delay
        tv1?.apply { alpha = 1f; this.text = l1 }
        tv2?.apply { alpha = 1f; this.text = l2 }

        // Restart the silence timer
        rescheduleSilenceClear()
    }

    // ── Two-line split ────────────────────────────────────────────────────────

    /**
     * Split [text] into two balanced lines.
     *
     * Strategy:
     *  1. If the whole text fits on one line → (text, "")
     *  2. Otherwise find the space closest to the midpoint and split there,
     *     so both lines are roughly equal length.
     *  3. If no space found (one very long word) → hard-split at MAX_CHARS_PER_LINE.
     */
    private fun splitToTwoLines(text: String): Pair<String, String> {
        if (text.length <= MAX_CHARS_PER_LINE) return Pair(text, "")

        val mid = text.length / 2
        // Search outward from midpoint for a space
        var bestIdx = -1
        for (delta in 0..mid) {
            val idxLeft  = mid - delta
            val idxRight = mid + delta
            if (idxLeft >= 0 && text[idxLeft] == ' ') { bestIdx = idxLeft; break }
            if (idxRight < text.length && text[idxRight] == ' ') { bestIdx = idxRight; break }
        }

        return if (bestIdx > 0) {
            Pair(text.substring(0, bestIdx).trim(), text.substring(bestIdx + 1).trim())
        } else {
            // No space found — hard split
            Pair(text.substring(0, MAX_CHARS_PER_LINE), text.substring(MAX_CHARS_PER_LINE).trim())
        }
    }

    // ── Silence clear ─────────────────────────────────────────────────────────

    private fun rescheduleSilenceClear() {
        silenceClearRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceClearRunnable = Runnable { fadeOutAndClear() }
        mainHandler.postDelayed(silenceClearRunnable!!, CLEAR_AFTER_MS)
    }

    private fun fadeOutAndClear() {
        tv1?.animate()?.alpha(0f)?.setDuration(600)?.start()
        tv2?.animate()?.alpha(0f)?.setDuration(600)?.start()
        mainHandler.postDelayed({
            tv1?.apply { text = ""; alpha = 1f }
            tv2?.apply { text = ""; alpha = 1f }
        }, 650)
    }

    // ── Overlay construction ──────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
            }

            fun makeSubtitleTv() = TextView(this).apply {
                text     = ""
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setShadowLayer(12f, 1f, 1f, Color.BLACK)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(2), dp(12), dp(2))
                maxLines  = 1
                gravity   = Gravity.START
                ellipsize = null
            }

            tv1 = makeSubtitleTv()
            tv2 = makeSubtitleTv()

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(tv1, lp)
            container.addView(tv2, lp)

            overlayView = container

            val sw = resources.displayMetrics.widthPixels
            params = WindowManager.LayoutParams(
                sw,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = dp(80)
            }

            // Draggable
            var startRawX = 0f; var startRawY = 0f
            var initX = 0;      var initY     = 0
            container.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX = p.x;        initY     = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID, "Caption Lens Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
