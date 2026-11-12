package app.dpadmouse

import android.content.Context
import android.view.KeyEvent

/**
 * Settings. Values read on hot paths (every key event, every cursor tick) are cached in memory
 * (write-through to SharedPreferences) so they never touch the SharedPreferences lock/map there.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("dpadmouse", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", 5555)
        set(v) = sp.edit().putInt("port", v).apply()

    /** 1..100 */
    @Volatile
    var sensitivity: Int = sp.getInt("sensitivity", 50).coerceIn(1, 100)
        set(v) {
            val n = v.coerceIn(1, 100)
            field = n
            sp.edit().putInt("sensitivity", n).apply()
        }

    /** UI language: "en" or "vi". */
    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(v) = sp.edit().putString("language", v).apply()

    /** Whether mouse mode was on the last time the app ran; used to resume it automatically on launch. */
    var wasMouseOn: Boolean
        get() = sp.getBoolean("was_mouse_on", false)
        set(v) = sp.edit().putBoolean("was_mouse_on", v).apply()

    // Mappable keys (cached). 0 (KEYCODE_UNKNOWN) = not used.
    @Volatile
    var toggleKey: Int = sp.getInt("key_toggle", DEF_TOGGLE)
        set(v) { field = v; sp.edit().putInt("key_toggle", v).apply() }

    @Volatile
    var clickKey: Int = sp.getInt("key_click", DEF_CLICK)
        set(v) { field = v; sp.edit().putInt("key_click", v).apply() }

    @Volatile
    var rightClickKey: Int = sp.getInt("key_right", KeyEvent.KEYCODE_UNKNOWN)
        set(v) { field = v; sp.edit().putInt("key_right", v).apply() }

    @Volatile
    var scrollUpKey: Int = sp.getInt("key_scroll_up", DEF_SCROLL_UP)
        set(v) { field = v; sp.edit().putInt("key_scroll_up", v).apply() }

    @Volatile
    var scrollDownKey: Int = sp.getInt("key_scroll_down", DEF_SCROLL_DOWN)
        set(v) { field = v; sp.edit().putInt("key_scroll_down", v).apply() }

    // Horizontal scroll: unmapped by default.
    @Volatile
    var scrollLeftKey: Int = sp.getInt("key_scroll_left", KeyEvent.KEYCODE_UNKNOWN)
        set(v) { field = v; sp.edit().putInt("key_scroll_left", v).apply() }

    @Volatile
    var scrollRightKey: Int = sp.getInt("key_scroll_right", KeyEvent.KEYCODE_UNKNOWN)
        set(v) { field = v; sp.edit().putInt("key_scroll_right", v).apply() }

    fun resetKeys() {
        toggleKey = DEF_TOGGLE
        clickKey = DEF_CLICK
        rightClickKey = KeyEvent.KEYCODE_UNKNOWN
        scrollUpKey = DEF_SCROLL_UP
        scrollDownKey = DEF_SCROLL_DOWN
        scrollLeftKey = KeyEvent.KEYCODE_UNKNOWN
        scrollRightKey = KeyEvent.KEYCODE_UNKNOWN
    }

    private companion object {
        const val DEF_TOGGLE = KeyEvent.KEYCODE_MENU
        const val DEF_CLICK = KeyEvent.KEYCODE_DPAD_CENTER
        const val DEF_SCROLL_UP = KeyEvent.KEYCODE_CHANNEL_UP
        const val DEF_SCROLL_DOWN = KeyEvent.KEYCODE_CHANNEL_DOWN
    }
}
