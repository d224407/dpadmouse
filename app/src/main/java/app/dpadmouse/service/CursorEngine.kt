package app.dpadmouse.service

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.dpadmouse.DLog
import app.dpadmouse.Prefs
import app.dpadmouse.hid.HidMouse
import app.dpadmouse.shell.ShellBackend
import java.io.IOException

/**
 * Turns key state (held/released) into smooth HID reports: a ~60Hz loop, uniformly accelerating while a key is held.
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
    private var nextScrollAt = 0L
    private var holdStart = 0L
    private var lastTickAt = 0L
    private var lastSpeed = 0f
    private var remX = 0f
    private var remY = 0f
    private var ticking = false
    private var failed = false

    fun press(dir: Int) = handler.post {
        if (held == 0) {
            val now = SystemClock.uptimeMillis()
            holdStart = now
            lastTickAt = now - TICK_MS
            lastSpeed = startSpeed()
            remX = 0f; remY = 0f
        }
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
            // Only restart the throttle on a new press: key auto-repeat calls this again and again,
            // and resetting here made scrolling run at the repeat rate instead of SCROLL_INTERVAL_MS.
            if (was != dir) nextScrollAt = 0L
            startTicking()
        }
    }

    /** dir: 1 = scroll right, -1 = scroll left, 0 = stop. */
    fun setPan(dir: Int) = handler.post {
        val was = panDir
        panDir = dir
        if (dir != 0) {
            if (was != dir) nextScrollAt = 0L
            startTicking()
        }
    }

    fun releaseAll() = handler.post {
        held = 0
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
            if (failed || (held == 0 && scrollDir == 0 && panDir == 0)) {
                ticking = false
                return
            }
            val now = SystemClock.uptimeMillis()

            val dirX = (if (held and RIGHT != 0) 1 else 0) - (if (held and LEFT != 0) 1 else 0)
            val dirY = (if (held and DOWN != 0) 1 else 0) - (if (held and UP != 0) 1 else 0)
            if (dirX != 0 || dirY != 0) {
                // Uniform acceleration: v = v0 + a*t up to vMax, distance integrated over the real elapsed time
                // (trapezoid), so jitter in the loop never makes the motion uneven.
                val sens = prefs.sensitivity
                val t = (now - holdStart) / 1000f
                val v = minOf(startSpeed() + (ACC_BASE + ACC_PER * sens) * t, VMAX_BASE + VMAX_PER * sens)
                val dt = (now - lastTickAt).coerceIn(0L, MAX_DT_MS) / 1000f
                var speed = (lastSpeed + v) * 0.5f * dt
                lastSpeed = v
                lastTickAt = now
                if (dirX != 0 && dirY != 0) speed *= 0.7071f
                val fx = dirX * speed + remX
                val fy = dirY * speed + remY
                val dx = fx.toInt()
                val dy = fy.toInt()
                remX = fx - dx
                remY = fy - dy
                sendMove(dx, dy)
            }

            if ((scrollDir != 0 || panDir != 0) && now >= nextScrollAt) {
                report(buttons, 0, 0, scrollDir, panDir)
                nextScrollAt = now + SCROLL_INTERVAL_MS
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Initial speed in px/s. */
    private fun startSpeed() = V0_BASE + V0_PER * prefs.sensitivity

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
        private const val MAX_DT_MS = 64L

        // Cursor motion in px/s, px/s^2 (all grow with the sensitivity setting 1..100).
        private const val V0_BASE = 40f
        private const val V0_PER = 2f
        private const val ACC_BASE = 400f
        private const val ACC_PER = 40f
        private const val VMAX_BASE = 600f
        private const val VMAX_PER = 40f
        private const val SCROLL_INTERVAL_MS = 120L
    }
}
