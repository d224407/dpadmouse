package app.dpadmouse.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.dpadmouse.DLog
import app.dpadmouse.MouseHub

/**
 * Intercepts controller keys (flagRequestFilterKeyEvents). While mouse mode is ON:
 *   arrows = move, OK/Enter = left click, mapped keys = right click / scroll.
 * The mouse on/off key is always captured, even while the mouse is off.
 */
class MouseAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        DLog.i("Accessibility service connected")
        MouseHub.attachService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        MouseHub.engine?.releaseAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DLog.i("Accessibility service unbound")
        MouseHub.detachService()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Mapping a key: release all keys to the dialog (including the mouse on/off key).
        if (MouseHub.learningKeys) return false

        val prefs = MouseHub.prefs
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        DLog.d(
            "key ${KeyEvent.keyCodeToString(code)} ${if (down) "DOWN" else "UP"} " +
                "rep=${event.repeatCount} dev=${event.deviceId} mouse=${MouseHub.mouseModeOn}"
        )

        return KeyRouter.handle(prefs, code, down, first)
    }
}
