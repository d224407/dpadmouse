package app.dpadmouse.service

import android.view.KeyEvent
import app.dpadmouse.Prefs

/** Linux scan code <-> Android key code for the EVIOCGRAB mode. */
object ScanMap {
    // Standard codes (Generic.kl); learned scan codes override these.
    private val BASE: Map<Int, Int> = HashMap<Int, Int>().apply {
        put(103, KeyEvent.KEYCODE_DPAD_UP)
        put(108, KeyEvent.KEYCODE_DPAD_DOWN)
        put(105, KeyEvent.KEYCODE_DPAD_LEFT)
        put(106, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(28, KeyEvent.KEYCODE_ENTER)
        put(96, KeyEvent.KEYCODE_NUMPAD_ENTER)
        for (c in intArrayOf(232, 352, 353)) put(c, KeyEvent.KEYCODE_DPAD_CENTER)
        put(139, KeyEvent.KEYCODE_MENU)
        put(158, KeyEvent.KEYCODE_BACK)
        put(172, KeyEvent.KEYCODE_HOME)
        put(1, KeyEvent.KEYCODE_ESCAPE)
        put(14, KeyEvent.KEYCODE_DEL)
        put(15, KeyEvent.KEYCODE_TAB)
        put(57, KeyEvent.KEYCODE_SPACE)
        put(104, KeyEvent.KEYCODE_PAGE_UP)
        put(109, KeyEvent.KEYCODE_PAGE_DOWN)
        put(402, KeyEvent.KEYCODE_CHANNEL_UP)
        put(403, KeyEvent.KEYCODE_CHANNEL_DOWN)
        put(115, KeyEvent.KEYCODE_VOLUME_UP)
        put(114, KeyEvent.KEYCODE_VOLUME_DOWN)
        put(113, KeyEvent.KEYCODE_VOLUME_MUTE)
        put(164, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        put(358, KeyEvent.KEYCODE_INFO)
        put(365, KeyEvent.KEYCODE_GUIDE)
        put(386, KeyEvent.KEYCODE_TV)
        put(141, KeyEvent.KEYCODE_SETTINGS)
        put(398, KeyEvent.KEYCODE_PROG_RED)
        put(399, KeyEvent.KEYCODE_PROG_GREEN)
        put(400, KeyEvent.KEYCODE_PROG_YELLOW)
        put(401, KeyEvent.KEYCODE_PROG_BLUE)
        for (i in 0..8) put(2 + i, KeyEvent.KEYCODE_1 + i)
        put(11, KeyEvent.KEYCODE_0)
        letters(this, 16, "QWERTYUIOP")
        letters(this, 30, "ASDFGHJKL")
        letters(this, 44, "ZXCVBNM")
    }

    private fun letters(m: MutableMap<Int, Int>, first: Int, row: String) {
        row.forEachIndexed { i, ch -> m[first + i] = KeyEvent.KEYCODE_A + (ch - 'A') }
    }

    /** Keys that are never blocked, whatever the mapping or the user whitelist says. */
    private val PROTECTED_KEYS = setOf(
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_STB_POWER,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE
    )

    /** Same keys as Linux scan codes (power, sleep, wakeup, home x2, back, mute, volume down/up). */
    private val PROTECTED_SCANS = intArrayOf(116, 142, 143, 102, 172, 158, 113, 114, 115)

    fun isProtected(prefs: Prefs, scan: Int, key: Int): Boolean =
        key in PROTECTED_KEYS || scan in PROTECTED_SCANS || scan in prefs.whitelist().keys ||
            key in prefs.whitelist().values

    class Plan(val consume: List<Int>, val protect: List<Int>, val map: Map<Int, Int>)

    /**
     * What the helper must consume / never consume, and the scan -> key code map used to route its events.
     * Mouse off: only the on/off key. Mouse on: every key the mouse uses. While mapping keys: nothing.
     */
    fun build(prefs: Prefs, mouseOn: Boolean, learning: Boolean): Plan {
        val map = HashMap(BASE)
        map.putAll(prefs.learnedScans())
        val white = prefs.whitelist()
        val protectedKeys = PROTECTED_KEYS + white.values
        val protect = (PROTECTED_SCANS.toList() + white.keys + map.filter { it.value in PROTECTED_KEYS }.keys)
            .distinct().sorted()
        if (learning) return Plan(emptyList(), protect, map)
        val wanted = HashSet<Int>()
        wanted.add(prefs.toggleKey)
        if (mouseOn) {
            wanted.addAll(
                listOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, prefs.clickKey, prefs.rightClickKey, prefs.scrollUpKey,
                    prefs.scrollDownKey, prefs.scrollLeftKey, prefs.scrollRightKey
                )
            )
            if (prefs.clickKey == KeyEvent.KEYCODE_DPAD_CENTER) {
                wanted.add(KeyEvent.KEYCODE_ENTER)
                wanted.add(KeyEvent.KEYCODE_NUMPAD_ENTER)
            }
        }
        wanted.remove(KeyEvent.KEYCODE_UNKNOWN)
        val consume = map.filter { it.value in wanted && it.value !in protectedKeys && it.key !in protect }
            .keys.sorted()
        return Plan(consume, protect, map)
    }
}
