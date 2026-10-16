package app.dpadmouse.service

import android.view.KeyEvent
import app.dpadmouse.MouseHub
import app.dpadmouse.Prefs
import app.dpadmouse.hid.HidMouse

/**
 * Turns an Android key code + down/up state into cursor-engine calls. Used
 * by [MouseAccessibilityService]; kept separate from the service class so
 * the dispatch logic can be tested/read independently of the Android
 * AccessibilityService plumbing.
 *
 * Returns true if the key was consumed (acted on), matching the convention
 * AccessibilityService.onKeyEvent uses to decide whether to swallow a key.
 */
object KeyRouter {
    fun handle(prefs: Prefs, code: Int, down: Boolean, first: Boolean): Boolean {
        if (code != KeyEvent.KEYCODE_UNKNOWN && code == prefs.toggleKey) {
            if (first) MouseHub.toggleFromService()
            return true
        }
        if (!MouseHub.mouseModeOn) return false
        val engine = MouseHub.engine ?: return false

        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP -> CursorEngine.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> CursorEngine.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> CursorEngine.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> CursorEngine.RIGHT
            KeyEvent.KEYCODE_DPAD_UP_LEFT -> CursorEngine.UP or CursorEngine.LEFT
            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> CursorEngine.UP or CursorEngine.RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> CursorEngine.DOWN or CursorEngine.LEFT
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> CursorEngine.DOWN or CursorEngine.RIGHT
            else -> 0
        }
        if (dir != 0) {
            if (down) { if (first) engine.press(dir) } else engine.release(dir)
            return true
        }

        if (isClickKey(code, prefs.clickKey)) {
            if (down) { if (first) engine.buttonDown(HidMouse.BTN_LEFT) } else engine.buttonUp(HidMouse.BTN_LEFT)
            return true
        }
        if (code != KeyEvent.KEYCODE_UNKNOWN && code == prefs.rightClickKey) {
            if (down) { if (first) engine.buttonDown(HidMouse.BTN_RIGHT) } else engine.buttonUp(HidMouse.BTN_RIGHT)
            return true
        }
        if (code != KeyEvent.KEYCODE_UNKNOWN && code == prefs.scrollUpKey) {
            engine.setScroll(if (down) 1 else 0)
            return true
        }
        if (code != KeyEvent.KEYCODE_UNKNOWN && code == prefs.scrollDownKey) {
            engine.setScroll(if (down) -1 else 0)
            return true
        }
        return false
    }

    private fun isClickKey(code: Int, clickKey: Int): Boolean {
        if (code == KeyEvent.KEYCODE_UNKNOWN || clickKey == KeyEvent.KEYCODE_UNKNOWN) return false
        if (code == clickKey) return true
        return clickKey == KeyEvent.KEYCODE_DPAD_CENTER &&
            (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
