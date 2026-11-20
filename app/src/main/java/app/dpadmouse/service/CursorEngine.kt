package app.dpadmouse.service

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.dpadmouse.DLog
import app.dpadmouse.Prefs
import app.dpadmouse.hid.HidMouse
import app.dpadmouse.shell.ShellBackend
import java.io.IOException
import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns key state (held/released) into smooth HID reports: a ~60Hz loop with eased, accelerating velocity.
 * Everything runs on its own thread ("dpadmouse-hid") so it never blocks the UI/Accessibility.
 */
class CursorEngine(
    private val backend: ShellBackend,
    private val prefs: Prefs,
    private val onFailure: (Throwable) -> Unit
) {
    private val thread = HandlerThread("dpadmouse-hid", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // Only accessed on the handler thread
    private var held = 0
    private var buttons = 0
    private var scrollDir = 0   // vertical: 1 = up, -1 = down
    private var panDir = 0      // horizontal: 1 = right, -1 = left
    private var scrollStart = 0L
    private var panStart = 0L
    private var sv = 0f // eased scroll velocity, notches/s (+ = up)
    private var pv = 0f // eased pan velocity, notches/s (+ = right)
    private var sRem = 0f
    private var pRem = 0f
    private var holdStart = 0L
    private var lastTickAt = 0L
    private var vx = 0f // eased velocity, px/s
    private var vy = 0f
    private var remX = 0f
    private var remY = 0f
    private var ticking = false
    private var failed = false

    fun press(dir: Int) = handler.post {
        if (held == 0) holdStart = SystemClock.uptimeMillis() // the speed ramp restarts on every new press
        held = held or dir
        startTicking()
    }

    fun release(dir: Int) = handler.post { held = held and dir.inv() }

    fun buttonDown(mask: Int) = handler.post {
        buttons = buttons or mask
        report(buttons, 0, 0, 0)
    }

    fun buttonUp(mask: Int) = handler.post {
        buttons = buttons and mask.inv()
        report(buttons, 0, 0, 0)
    }

    /** dir: 1 = scroll up, -1 = scroll down, 0 = stop. */
    fun setScroll(dir: Int) = handler.post {
        val was = scrollDir
        scrollDir = dir
        if (dir != 0) {
            // Restart the ramp only on a new press: key auto-repeat calls this again and again.
            if (was != dir) scrollStart = SystemClock.uptimeMillis()
            startTicking()
        }
    }

    /** dir: 1 = scroll right, -1 = scroll left, 0 = stop. */
    fun setPan(dir: Int) = handler.post {
        val was = panDir
        panDir = dir
        if (dir != 0) {
            if (was != dir) panStart = SystemClock.uptimeMillis()
            startTicking()
        }
    }

    fun releaseAll() = handler.post {
        held = 0
        stopMotion()
        scrollDir = 0
        panDir = 0
        if (buttons != 0) {
            buttons = 0
            report(0, 0, 0, 0)
        }
    }

    // --- used by the test buttons in the debug build
    fun nudge(dx: Int, dy: Int) = handler.post { sendMove(dx, dy) }

    fun click() = handler.post {
        report(HidMouse.BTN_LEFT, 0, 0, 0)
        report(0, 0, 0, 0)
    }

    fun shutdown() {
        handler.post {
            held = 0
            stopMotion()
            scrollDir = 0
            panDir = 0
            ticking = false
            handler.removeCallbacks(tick)
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ internal

    private fun startTicking() {
        if (!ticking && !failed) {
            ticking = true
            handler.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val moving = held != 0 || abs(vx) > STOP_SPEED || abs(vy) > STOP_SPEED
            val scrolling = scrollDir != 0 || panDir != 0 || abs(sv) > SCROLL_STOP || abs(pv) > SCROLL_STOP
            if (failed || (!moving && !scrolling)) {
                stopMotion()
                ticking = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = if (lastTickAt == 0L) TICK_MS / 1000f else minOf((now - lastTickAt) / 1000f, MAX_DT)
            lastTickAt = now

            val dirX = (if (held and RIGHT != 0) 1 else 0) - (if (held and LEFT != 0) 1 else 0)
            val dirY = (if (held and DOWN != 0) 1 else 0) - (if (held and UP != 0) 1 else 0)
            if (moving) {
                var tx = dirX.toFloat()
                var ty = dirY.toFloat()
                if (tx != 0f && ty != 0f) {
                    tx *= 0.7071f
                    ty *= 0.7071f
                }
                // Target speed grows with the hold time (quadratic ramp); the velocity eases toward it, so
                // starts, turns and stops are smooth instead of instant.
                val k = 0.4f + 0.012f * prefs.sensitivity
                val ratio = minOf((now - holdStart) / 1000f / ACCEL_TIME, 1f)
                val target = (BASE_SPEED + (MAX_SPEED - BASE_SPEED) * ratio * ratio) * k
                val response = if (held != 0) RESPONSE else DECEL
                val a = 1f - exp(-response * dt)
                vx += (tx * target - vx) * a
                vy += (ty * target - vy) * a
                val fx = vx * dt + remX
                val fy = vy * dt + remY
                val dx = fx.toInt()
                val dy = fy.toInt()
                remX = fx - dx
                remY = fy - dy
                sendMove(dx, dy)
            }

            if (scrolling) {
                sv = easeScroll(sv, scrollDir, scrollStart, now, dt)
                pv = easeScroll(pv, panDir, panStart, now, dt)
                val fs = sv * dt + sRem
                val fp = pv * dt + pRem
                val ws = fs.toInt().coerceIn(-127, 127)
                val wp = fp.toInt().coerceIn(-127, 127)
                sRem = fs - ws
                pRem = fp - wp
                if (ws != 0 || wp != 0) report(buttons, 0, 0, ws, wp)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Eases the scroll velocity toward a target that ramps up with the hold time. */
    private fun easeScroll(v: Float, dir: Int, start: Long, now: Long, dt: Float): Float {
        val ratio = minOf((now - start) / 1000f / SCROLL_ACCEL_TIME, 1f)
        val target = dir * (SCROLL_BASE + (SCROLL_MAX - SCROLL_BASE) * ratio * ratio)
        val response = if (dir != 0) SCROLL_RESPONSE else SCROLL_DECEL
        return v + (target - v) * (1f - exp(-response * dt))
    }

    private fun stopMotion() {
        vx = 0f
        vy = 0f
        remX = 0f
        remY = 0f
        sv = 0f
        pv = 0f
        sRem = 0f
        pRem = 0f
        lastTickAt = 0L
    }

    private fun sendMove(dxIn: Int, dyIn: Int) {
        var dx = dxIn
        var dy = dyIn
        while ((dx != 0 || dy != 0) && !failed) {
            val sx = dx.coerceIn(-127, 127)
            val sy = dy.coerceIn(-127, 127)
            report(buttons, sx, sy, 0)
            dx -= sx
            dy -= sy
        }
    }

    private fun report(b: Int, dx: Int, dy: Int, wheel: Int, pan: Int = 0) {
        if (failed) return
        val json = HidMouse.reportJson(b, dx, dy, wheel, pan)
        DLog.v("report $json")
        try {
            backend.writeHid(json)
        } catch (e: IOException) {
            failed = true
            held = 0
            scrollDir = 0
            panDir = 0
            DLog.e("Failed to write HID report", e)
            onFailure(e)
        }
    }

    companion object {
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 4
        const val RIGHT = 8

        private const val TICK_MS = 16L
        private const val MAX_DT = 0.05f // s

        // Cursor motion (px/s), same easing model as the TvBrowser app; scaled by the sensitivity setting.
        private const val BASE_SPEED = 300f
        private const val MAX_SPEED = 3000f
        private const val ACCEL_TIME = 0.8f // s of holding to reach the max speed
        private const val RESPONSE = 16f // velocity easing while held
        private const val DECEL = 12f // velocity easing after release
        private const val STOP_SPEED = 4f

        // Scroll (wheel notches/s): eased and ramped like the cursor, sent every tick as whole notches.
        private const val SCROLL_BASE = 8f
        private const val SCROLL_MAX = 28f
        private const val SCROLL_ACCEL_TIME = 0.9f // s
        private const val SCROLL_RESPONSE = 14f
        private const val SCROLL_DECEL = 22f
        private const val SCROLL_STOP = 0.5f
    }
}
