package app.dpadmouse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.dpadmouse.ui.M3Switch
import app.dpadmouse.ui.TvSlider
import app.dpadmouse.ui.Ui
import kotlin.reflect.KMutableProperty0

/**
 * Android TV UI. Dark only, designed for D-pad navigation: every control is
 * focusable with a visible focus state, text is sized for a 10-foot view, and
 * content stays inside the overscan-safe margins. Text entry (address/port)
 * happens in a dialog instead of inline fields so the D-pad never gets trapped
 * inside an EditText.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: Ui
    private val prefs get() = MouseHub.prefs

    private enum class Page { MAIN, KEYS, LOG }

    private var page = Page.MAIN
    /** The row that opened the current sub-page; gets focus back when returning. */
    private var returnFocus: View? = null

    private lateinit var mainPage: View
    private lateinit var keysPage: View
    private var logPage: View? = null

    // --- main page
    private lateinit var subtitle: TextView
    private lateinit var masterRow: Ui.Row
    private lateinit var masterSwitch: M3Switch
    private lateinit var statusRow: Ui.Row
    private lateinit var a11yRow: Ui.Row
    private lateinit var hostChip: TextView
    private lateinit var portChip: TextView
    private lateinit var sensRow: Ui.Row
    private lateinit var sensSlider: TvSlider
    private lateinit var keysRow: Ui.Row
    private var storageRow: Ui.Row? = null
    private var openLogRow: Ui.Row? = null

    // --- "Map keys" page
    private val keySlots = mutableListOf<Triple<Slot, TextView, Ui.Row>>()

    // --- log page (debug only)
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var logFilter: Char? = null
    private val filterChips = LinkedHashMap<Char?, TextView>()

    private class Slot(val title: String, val desc: String, val prop: KMutableProperty0<Int>)

    private val stateListener: () -> Unit = { render() }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showPage(Page.MAIN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.lang = prefs.language
        ui = Ui(this)

        // Overscan-safe margins (Android TV guideline: 48dp sides, 27dp top/bottom).
        val root = FrameLayout(this).apply {
            setBackgroundColor(ui.c(R.color.md_surface))
            setPadding(ui.dp(48), ui.dp(27), ui.dp(48), ui.dp(27))
            clipChildren = false
        }
        mainPage = buildMainPage()
        keysPage = buildKeysPage().also { it.visibility = View.GONE }
        root.addView(mainPage, matchParent())
        root.addView(keysPage, matchParent())
        if (DebugTools.ENABLED) {
            val log = buildLogPage()
            log.visibility = View.GONE
            logPage = log
            root.addView(log, matchParent())
        }

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backCallback)
        masterRow.post { masterRow.requestFocus() }

        if (DebugTools.ENABLED) DebugTools.requestStorageAccess(this)
    }

    override fun onStart() {
        super.onStart()
        MouseHub.addListener(stateListener)
        if (DebugTools.ENABLED) DLog.listener = { runOnUiThread { renderLog() } }
        render()
        if (DebugTools.ENABLED) renderLog()
    }

    override fun onStop() {
        MouseHub.removeListener(stateListener)
        if (DebugTools.ENABLED) DLog.listener = null
        super.onStop()
    }

    // ================================================================== pages

    private fun showPage(p: Page) {
        val previous = page
        page = p
        mainPage.visibility = if (p == Page.MAIN) View.VISIBLE else View.GONE
        keysPage.visibility = if (p == Page.KEYS) View.VISIBLE else View.GONE
        logPage?.visibility = if (p == Page.LOG) View.VISIBLE else View.GONE
        backCallback.isEnabled = p != Page.MAIN
        when (p) {
            Page.MAIN -> (if (previous != Page.MAIN) returnFocus else null)?.requestFocus() ?: masterRow.requestFocus()
            Page.KEYS -> keySlots.first().third.requestFocus()
            Page.LOG -> {
                renderLog()
                logScroll?.requestFocus()
            }
        }
    }

    private fun openPage(p: Page, from: View) {
        returnFocus = from
        showPage(p)
    }

    // ================================================================== main page

    private fun buildMainPage(): View {
        // --- header: title on the left, language selector in the top-right corner.
        // It is the first item of the scrolling content, so it scrolls away with the list.
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(ui.label(Strings.appTitle, 38f, R.color.md_on_surface, bold = true).apply {
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
        })
        subtitle = ui.label("", 17f, R.color.md_on_surface_variant).apply {
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), 0)
        }
        titleCol.addView(subtitle)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            ui.pill(Strings.languageButtonLabel) { showLanguageDialog() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // --- content (scrolls; the focused row is scrolled into view automatically)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            // Room for the focus scale-up so focused rows are never clipped at the sides.
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(32))
        }
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // VIRTUAL MOUSE
        col.addView(ui.listTitle(Strings.sectionVirtualMouse))
        masterSwitch = M3Switch(ui)
        masterRow = ui.Row(Strings.mouseModeRow, "", masterSwitch) { onMasterClick() }
        col.addView(ui.group(masterRow))

        // LOCAL ADB
        col.addView(ui.listTitle(Strings.sectionLocalAdb))
        statusRow = ui.Row(Strings.statusRowName, "")
        hostChip = ui.chip(prefs.host)
        portChip = ui.chip(prefs.port.toString())
        val hostRow = ui.Row(Strings.addressRowName, Strings.addressRowDesc, hostChip) {
            showInputDialog(Strings.addressRowName, prefs.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { v ->
                prefs.host = v.ifEmpty { "127.0.0.1" }
                render()
            }
        }
        val portRow = ui.Row(Strings.portRowName, Strings.portRowDesc, portChip) {
            showInputDialog(Strings.portRowName, prefs.port.toString(), InputType.TYPE_CLASS_NUMBER) { v ->
                val p = v.toIntOrNull()
                if (p != null && p in 1..65535) {
                    prefs.port = p
                    render()
                } else {
                    toast(Strings.invalidPortToast)
                }
            }
        }
        col.addView(ui.group(statusRow, hostRow, portRow))
        col.addView(
            ui.actionRow(
                ui.pill(Strings.connectBtn, primary = true) { MouseHub.connect() },
                ui.pill(Strings.disconnectBtn) { MouseHub.disconnect() }
            )
        )

        // CONTROL
        col.addView(ui.listTitle(Strings.sectionControl))
        sensSlider = TvSlider(ui, 1, 100).apply {
            value = prefs.sensitivity
            onChange = { setSensitivity(it) }
        }
        sensRow = ui.Row(Strings.sensitivityRowName, Strings.sensitivityDesc(prefs.sensitivity), sensSlider, stacked = true)
        sensRow.enableFocus()
        // Left/Right on the focused row adjust the value; holding the key speeds it up.
        sensRow.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val step = when {
                        ev.repeatCount < 8 -> 1
                        ev.repeatCount < 25 -> 3
                        else -> 6
                    }
                    setSensitivity(prefs.sensitivity + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step)
                }
                true
            } else {
                false
            }
        }
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(ui.c(R.color.md_on_surface_variant))
        }
        keysRow = ui.Row(Strings.mapKeysRowName, "", chevron) { openPage(Page.KEYS, keysRow) }
        col.addView(ui.group(sensRow, keysRow))

        // ACCESSIBILITY (status only: turning mouse mode on enables the service automatically)
        col.addView(ui.listTitle(Strings.sectionAccessibility))
        // Selecting it retries enabling right away (recovery path now that there is no button).
        a11yRow = ui.Row(Strings.a11yServiceRowName, "") { retryAccessibility() }
        col.addView(ui.group(a11yRow))

        // DEBUG (debug build only)
        if (DebugTools.ENABLED) {
            col.addView(ui.listTitle(Strings.sectionDebug))
            val storage = ui.Row(Strings.storageRowName(DebugTools.DIR), "") { DebugTools.requestStorageAccess(this) }
            storageRow = storage
            val logRow = ui.Row(Strings.openLogRowName, Strings.openLogRowDesc) { openPage(Page.LOG, openLogRow!!) }
            openLogRow = logRow
            col.addView(
                ui.group(
                    logRow,
                    ui.Row(Strings.nudgeRightRowName, Strings.nudgeRightRowDesc) {
                        val e = MouseHub.engine
                        if (e == null) toast(Strings.notConnectedToast) else e.nudge(120, 0)
                    },
                    ui.Row(Strings.nudgeDownRowName, Strings.nudgeDownRowDesc) {
                        val e = MouseHub.engine
                        if (e == null) toast(Strings.notConnectedToast) else e.nudge(0, 120)
                    },
                    ui.Row(Strings.testClickRowName, Strings.testClickRowDesc) {
                        val e = MouseHub.engine
                        if (e == null) toast(Strings.notConnectedToast) else e.click()
                    },
                    ui.Row(Strings.diagnoseRowName, Strings.diagnoseRowDesc) { diagnose() },
                    storage
                )
            )
        }

        // Note: the ScrollView must keep clipChildren = true (default) so scrolled-out rows are
        // cut at its edges instead of drawing over the screen margins.
        return ScrollView(this).apply {
            clipToPadding = false
            isSmoothScrollingEnabled = true
            addView(col)
        }
    }

    private fun retryAccessibility() {
        if (MouseHub.serviceConnected) return
        toast(Strings.a11yEnablingToast)
        MouseHub.enableAccessibilityService { r ->
            r.onFailure { toast("${Strings.diagnoseErrorPrefix}${it.message}") }
        }
    }

    private fun setSensitivity(v: Int) {
        prefs.sensitivity = v
        val n = prefs.sensitivity
        sensSlider.value = n
        sensRow.setDesc(Strings.sensitivityDesc(n))
    }

    private fun onMasterClick() {
        when {
            MouseHub.state == MouseHub.State.CONNECTING -> Unit
            MouseHub.mouseModeOn -> MouseHub.setMouseMode(false)
            MouseHub.state == MouseHub.State.CONNECTED -> MouseHub.setMouseMode(true)
            else -> MouseHub.toggleFromService() // connect then turn on
        }
    }

    private fun diagnose() {
        val cmd = "echo uid=$(id -u); getprop ro.build.version.release; getprop ro.build.version.sdk; " +
            "getprop ro.product.model; ls -l /system/bin/hid /dev/uhid 2>&1; " +
            "settings get secure enabled_accessibility_services"
        MouseHub.execOnce(cmd) { r ->
            DLog.i(Strings.diagnosePrefix + r.getOrElse { "${Strings.diagnoseErrorPrefix}${it.message}" })
        }
    }

    private fun render() {
        val st = MouseHub.state
        val on = MouseHub.mouseModeOn
        masterSwitch.checked = on
        masterRow.setActive(on)
        masterRow.setDesc(
            when {
                on -> Strings.masterDescOn
                st == MouseHub.State.CONNECTED -> Strings.masterDescConnectedOff
                st == MouseHub.State.CONNECTING -> Strings.masterDescConnecting
                else -> Strings.masterDescDefault
            }
        )
        val shell = when (st) {
            MouseHub.State.DISCONNECTED -> Strings.statusDisconnected
            MouseHub.State.CONNECTING -> Strings.statusConnecting
            MouseHub.State.CONNECTED -> Strings.statusConnected
        }
        statusRow.setDesc(shell + (MouseHub.lastError?.let { Strings.errorSuffix(it) } ?: ""))
        a11yRow.setDesc(if (MouseHub.serviceConnected) Strings.a11yRunning else Strings.a11yNotEnabled)
        subtitle.text = if (on) Strings.subtitleOn else Strings.subtitleOff
        hostChip.text = prefs.host
        portChip.text = prefs.port.toString()
        keysRow.setDesc(Strings.toggleDesc(keyName(prefs.toggleKey)))
        storageRow?.setDesc(if (DebugTools.hasStorageAccess(this)) Strings.storageGranted else Strings.storageNotGranted)
    }

    // ================================================================== dialogs

    /** Language is chosen from a list, not toggled back and forth. */
    private fun showLanguageDialog() {
        val langs = Strings.languages
        val checked = langs.indexOfFirst { it.code == prefs.language }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(Strings.languageRowName)
            .setSingleChoiceItems(langs.map { it.name }.toTypedArray(), checked) { d, which ->
                d.dismiss()
                val code = langs[which].code
                if (code != prefs.language) {
                    prefs.language = code
                    recreate()
                }
            }
            .setNegativeButton(Strings.cancelBtn, null)
            .show()
    }

    /** Text entry in a dialog, so the D-pad never gets trapped inside an inline EditText. */
    private fun showInputDialog(title: String, initial: String, inputType: Int, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            this.inputType = inputType
            setSingleLine()
            setSelectAllOnFocus(true)
            setTextColor(ui.c(R.color.md_on_surface))
            textSize = 22f
            imeOptions = EditorInfo.IME_ACTION_DONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val box = FrameLayout(this).apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(Strings.okBtn) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(Strings.cancelBtn, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                true
            } else {
                false
            }
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    // ================================================================== "Map keys" page

    private fun buildKeysPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        col.addView(pageHeader(Strings.mapKeysTitle) { showPage(Page.MAIN) })

        val slots = listOf(
            Slot(Strings.slotToggleTitle, Strings.slotToggleDesc, prefs::toggleKey),
            Slot(Strings.slotLeftClickTitle, Strings.slotLeftClickDesc, prefs::clickKey),
            Slot(Strings.slotRightClickTitle, Strings.slotRightClickDesc, prefs::rightClickKey),
            Slot(Strings.slotScrollUpTitle, Strings.slotScrollUpDesc, prefs::scrollUpKey),
            Slot(Strings.slotScrollDownTitle, Strings.slotScrollDownDesc, prefs::scrollDownKey),
            Slot(Strings.slotScrollLeftTitle, Strings.slotScrollLeftDesc, prefs::scrollLeftKey),
            Slot(Strings.slotScrollRightTitle, Strings.slotScrollRightDesc, prefs::scrollRightKey)
        )
        keySlots.clear()
        val rows = slots.map { slot ->
            val chip = ui.chip(keyName(slot.prop.get()))
            val row = ui.Row(slot.title, slot.desc, chip) {
                learn(slot) {
                    chip.text = keyName(slot.prop.get())
                    render()
                }
            }
            // Hold OK on a row to clear its key (the learn dialog captures every other key).
            row.setOnLongPress {
                slot.prop.set(KeyEvent.KEYCODE_UNKNOWN)
                chip.text = keyName(slot.prop.get())
                render()
                toast(Strings.keyClearedToast(slot.title))
            }
            keySlots.add(Triple(slot, chip, row))
            row
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(32))
            addView(ui.group(*rows.toTypedArray()))
            addView(
                ui.label(Strings.keysPageNote, 16f, R.color.md_on_surface_variant)
                    .apply { setPadding(ui.dp(8), ui.dp(16), ui.dp(8), 0) }
            )
            addView(ui.actionRow(ui.pill(Strings.resetDefaultsBtn) {
                prefs.resetKeys()
                keySlots.forEach { (slot, chip, _) -> chip.text = keyName(slot.prop.get()) }
                render()
            }))
        }
        col.addView(
            ScrollView(this).apply {
                clipToPadding = false
                isSmoothScrollingEnabled = true
                addView(content)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return col
    }

    /** Sub-page header: a back button (also reachable by pointer) and a title, plus optional trailing views. */
    private fun pageHeader(title: String, vararg trailing: View, onBack: () -> Unit): LinearLayout {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(12))
        }
        header.addView(ui.iconButton(R.drawable.ic_back, onBack))
        header.addView(
            ui.label(title, 30f, R.color.md_on_surface, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(20) }
        )
        trailing.forEachIndexed { i, v ->
            header.addView(v, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply {
                marginStart = if (i == 0) ui.dp(12) else ui.dp(10)
            })
        }
        return header
    }

    /** Listens for a key on the controller and stores it in [slot]. Back cancels. */
    private fun learn(slot: Slot, after: () -> Unit) {
        MouseHub.learningKeys = true // the accessibility service releases every key so the dialog can receive it
        val dlg = AlertDialog.Builder(this)
            .setTitle(slot.title)
            .setMessage(Strings.learnMessage)
            .setNegativeButton(Strings.dontUseKeyBtn) { _, _ ->
                slot.prop.set(KeyEvent.KEYCODE_UNKNOWN)
                after()
            }
            .create()
        dlg.setOnDismissListener { MouseHub.learningKeys = false }
        dlg.setOnKeyListener { d, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                false
            } else {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    slot.prop.set(keyCode)
                    after()
                    d.dismiss()
                }
                true
            }
        }
        dlg.show()
    }

    private fun keyName(code: Int): String =
        if (code == KeyEvent.KEYCODE_UNKNOWN) Strings.notUsedKeyName
        else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    // ================================================================== log page (debug)

    private fun buildLogPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        val save = ui.iconButton(R.drawable.ic_save) {
            DebugTools.saveLog(this, DLog.dumpAll()) { msg ->
                toast(if (msg.startsWith("Error")) msg else Strings.logSavedPrefix + msg)
            }
        }
        val copy = ui.iconButton(R.drawable.ic_copy) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DpadMouse log", DLog.dumpAll()))
            toast(Strings.logCopiedToast)
        }
        val clear = ui.iconButton(R.drawable.ic_delete) { DLog.clear() }
        col.addView(pageHeader(Strings.logTitle, save, copy, clear) { showPage(Page.MAIN) })

        val chips = LinearLayout(this).apply {
            clipChildren = false
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(14))
        }
        listOf<Pair<Char?, String>>(
            null to "ALL", 'D' to "DEBUG", 'I' to "INFO", 'W' to "WARN", 'E' to "ERROR"
        ).forEachIndexed { i, (level, text) ->
            val chip = ui.filterChip(text) { setLogFilter(level) }
            filterChips[level] = chip
            chips.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) marginStart = ui.dp(12) })
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chips)
        })
        setLogFilter(null)

        val text = ui.label("", 14f, R.color.md_on_surface_variant, mono = true).apply {
            setLineSpacing(0f, 1.3f)
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
        }
        logText = text
        val scroll = ScrollView(this).apply {
            background = ui.pillBg(R.color.md_surface_container_high, 20)
            ui.makeFocusable(this, 1f) // focus it, then scroll the log with the D-pad's Up/Down
            addView(text)
        }
        logScroll = scroll
        col.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { marginStart = ui.dp(12); marginEnd = ui.dp(12) })
        return col
    }

    private fun setLogFilter(level: Char?) {
        logFilter = level
        filterChips.forEach { (lv, chip) ->
            with(ui) { chip.setSelectedChip(lv == level, lv == 'E') }
        }
        renderLog()
    }

    private fun renderLog() {
        val tv = logText ?: return
        val sb = SpannableStringBuilder()
        for (e in DLog.snapshot(logFilter)) {
            val start = sb.length
            sb.append(e.text).append('\n')
            val color = when (e.level) {
                'E' -> ui.c(R.color.log_error)
                'W' -> ui.c(R.color.log_warn)
                else -> Color.TRANSPARENT
            }
            if (color != Color.TRANSPARENT) {
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        tv.text = sb
        // Not using fullScroll(): it steals the controller's focus back to the log pane on every new line.
        tv.post { logScroll?.scrollTo(0, tv.bottom) }
    }

    // ================================================================== utilities

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
