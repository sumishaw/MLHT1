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
 * OverlayService  —  Word-by-word real-time subtitle overlay
 *
 * Display behaviour (exactly as specified):
 *
 *   • Words from Whisper arrive one-by-one via appendWord().
 *   • Each word is appended (with a space) to the current line being built.
 *   • When Line 1 is full (exceeds MAX_CHARS_PER_LINE), the word that caused
 *     overflow becomes the first word of Line 2.
 *   • When Line 2 is full, word appending PAUSES and a 4-second read timer
 *     starts.
 *   • After 4 seconds both lines are cleared (wiped to empty strings) and
 *     appending resumes from Line 1 with any words that queued up during the
 *     read pause.
 *   • After silence (no new words for CLEAR_AFTER_MS) the overlay fades out.
 *
 * Layout:
 *   • Two fixed TextViews stacked vertically, always visible (no wrap).
 *   • Full screen width, anchored near the bottom, draggable.
 *   • White bold text with black shadow — readable over any video.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        // Called by SpeechCaptureService to push full sentences (kept for compat)
        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            // Full-sentence path: split into words and stream them
            instance?.enqueueWords(original, hindi.split(Regex("\\s+")).filter { it.isNotEmpty() })
        }

        // Word-by-word path called directly from SpeechCaptureService word streamer
        fun appendWord(original: String, word: String, isLastWord: Boolean) {
            latestOriginal = original
            instance?.onWordArrived(word, isLastWord)
        }

        @Volatile var instance: OverlayService? = null
    }

    // ── Tuning constants ───────────────────────────────────────────────────────

    // How many characters fit comfortably on one subtitle line at SP=22.
    // On a 12-inch tablet (≈800dp wide) this is roughly 38-42 chars.
    // Adjust down if text overflows the screen edge.
    private val MAX_CHARS_PER_LINE = 36

    // How long both lines stay visible after being filled before clearing
    private val READ_PAUSE_MS = 4_000L

    // How long after the last word before the overlay fades out (silence)
    private val CLEAR_AFTER_MS = 8_000L

    // ── State ──────────────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    // The two live subtitle line strings
    private var line1 = ""
    private var line2 = ""

    // Which line is currently being written to (1 or 2)
    private var activeLine = 1

    // True while the 4-second read timer is running — words queue up
    private var isPaused = false

    // Words that arrived during the read pause, replayed after clear
    private val wordQueue = ArrayDeque<String>()

    // View references
    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var tv1:           TextView?                   = null
    private var tv2:           TextView?                   = null
    private var params:        WindowManager.LayoutParams? = null
    @Volatile private var viewAdded = false
    @Volatile private var running   = false

    // Pending runnables
    private var readPauseRunnable: Runnable? = null
    private var silenceClearRunnable: Runnable? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

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

    // ── Public word entry points ───────────────────────────────────────────────

    /**
     * Called from SpeechCaptureService word-streamer for each individual word.
     * Must be called on the main thread (SpeechCaptureService posts via mainHandler).
     */
    fun onWordArrived(word: String, isLastWord: Boolean) {
        if (!running) return

        if (isPaused) {
            // Collect words during read-pause; they'll drain into Line 1 after clear
            wordQueue.addLast(word)
            return
        }

        appendWordToLines(word)

        // Reschedule silence-clear every time a word arrives
        rescheduleSilenceClear()
    }

    /**
     * Full-sentence fallback (used when updateText() is called directly).
     * Enqueues all words; they are processed the same as streamed words.
     */
    fun enqueueWords(original: String, words: List<String>) {
        mainHandler.post {
            words.forEach { onWordArrived(it, it == words.last()) }
        }
    }

    // ── Core line logic ────────────────────────────────────────────────────────

    private fun appendWordToLines(word: String) {
        when (activeLine) {
            1 -> {
                val candidate = if (line1.isEmpty()) word else "$line1 $word"
                if (candidate.length <= MAX_CHARS_PER_LINE) {
                    // Word fits on Line 1
                    line1 = candidate
                    refreshDisplay()
                } else {
                    // Line 1 is full — move this word to start of Line 2
                    activeLine = 2
                    line2 = word
                    refreshDisplay()
                }
            }
            2 -> {
                val candidate = if (line2.isEmpty()) word else "$line2 $word"
                if (candidate.length <= MAX_CHARS_PER_LINE) {
                    // Word fits on Line 2
                    line2 = candidate
                    refreshDisplay()
                } else {
                    // Both lines are now full — start 4-second read timer
                    // This word is the first word of the next "screen"
                    wordQueue.addFirst(word)   // re-queue it for after the pause
                    startReadPause()
                }
            }
        }
    }

    // ── Read pause (4 seconds) ─────────────────────────────────────────────────

    private fun startReadPause() {
        isPaused = true
        cancelReadPause()   // safety: shouldn't already be running

        readPauseRunnable = Runnable {
            // Clear both lines and reset to Line 1
            line1      = ""
            line2      = ""
            activeLine = 1
            isPaused   = false
            refreshDisplay()

            // Drain any words that queued up during the pause
            drainWordQueue()
        }
        mainHandler.postDelayed(readPauseRunnable!!, READ_PAUSE_MS)
    }

    private fun cancelReadPause() {
        readPauseRunnable?.let { mainHandler.removeCallbacks(it) }
        readPauseRunnable = null
    }

    private fun drainWordQueue() {
        // Process queued words one at a time; appendWordToLines may trigger
        // another read-pause partway through, which is fine — the remainder
        // will be left in wordQueue for the next drain cycle.
        while (!isPaused && wordQueue.isNotEmpty()) {
            appendWordToLines(wordQueue.removeFirst())
        }
    }

    // ── Silence clear ──────────────────────────────────────────────────────────

    private fun rescheduleSilenceClear() {
        silenceClearRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceClearRunnable = Runnable {
            // No words for CLEAR_AFTER_MS — fade everything out
            fadeOutAndClear()
        }
        mainHandler.postDelayed(silenceClearRunnable!!, CLEAR_AFTER_MS)
    }

    private fun fadeOutAndClear() {
        cancelReadPause()
        tv1?.animate()?.alpha(0f)?.setDuration(600)?.start()
        tv2?.animate()?.alpha(0f)?.setDuration(600)?.start()
        mainHandler.postDelayed({
            line1 = ""; line2 = ""
            activeLine = 1
            isPaused   = false
            wordQueue.clear()
            tv1?.apply { text = ""; alpha = 1f }
            tv2?.apply { text = ""; alpha = 1f }
        }, 650)
    }

    // ── Display refresh ────────────────────────────────────────────────────────

    private fun refreshDisplay() {
        tv1?.text = line1
        tv2?.text = line2
    }

    // ── Overlay construction ───────────────────────────────────────────────────

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
                maxLines = 1
                gravity  = Gravity.START
                // Prevent ellipsis — we control line breaks ourselves
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Notification ───────────────────────────────────────────────────────────

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
