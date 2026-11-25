package app.dpadmouse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import app.dpadmouse.service.ScanMap
import app.dpadmouse.ui.DpadTheme
import app.dpadmouse.ui.InputDialog
import app.dpadmouse.ui.KeySlotUi
import app.dpadmouse.ui.KeysScreen
import app.dpadmouse.ui.LanguageDialog
import app.dpadmouse.ui.LearnDialog
import app.dpadmouse.ui.LogLine
import app.dpadmouse.ui.LogScreen
import app.dpadmouse.ui.MainActions
import app.dpadmouse.ui.MainScreen
import app.dpadmouse.ui.MainState
import app.dpadmouse.ui.WhitelistDialog
import kotlin.reflect.KMutableProperty0
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * Android TV UI in Jetpack Compose (Material 3 Expressive, dark only), designed for D-pad use.
 * Content stays inside the overscan-safe margins. Text entry (address/port) happens in a dialog.
 * Plain MouseHub/Prefs values are re-read whenever [rev] changes (see [render]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MainActivity : ComponentActivity() {

    private val prefs get() = MouseHub.prefs

    private enum class Page { MAIN, KEYS, LOG }

    private class Slot(val title: String, val desc: String, val prop: KMutableProperty0<Int>)

    private sealed interface Dlg {
        data object Language : Dlg
        class Input(val title: String, val initial: String, val numeric: Boolean, val onOk: (String) -> Unit) : Dlg
        class Learn(val slot: Slot) : Dlg
        data object Whitelist : Dlg
        data object WhitelistLearn : Dlg
    }

    /** Bumped whenever MouseHub/Prefs change; composables read it to refresh. */
    private var rev by mutableIntStateOf(0)
    private var page by mutableStateOf(Page.MAIN)
    private var dialog by mutableStateOf<Dlg?>(null)
    private var logFilter by mutableStateOf<Char?>(null)

    private val masterFocus = FocusRequester()
    private val keysFocus = FocusRequester()
    private val logFocus = FocusRequester()
    private val keysFirstFocus = FocusRequester()
    private val logPaneFocus = FocusRequester()
    /** The row that opened the current sub-page; gets focus back when returning. */
    private var returnFocus: FocusRequester? = null

    private val slots by lazy {
        listOf(
            Slot(Strings.slotToggleTitle, Strings.slotToggleDesc, prefs::toggleKey),
            Slot(Strings.slotLeftClickTitle, Strings.slotLeftClickDesc, prefs::clickKey),
            Slot(Strings.slotRightClickTitle, Strings.slotRightClickDesc, prefs::rightClickKey),
            Slot(Strings.slotScrollUpTitle, Strings.slotScrollUpDesc, prefs::scrollUpKey),
            Slot(Strings.slotScrollDownTitle, Strings.slotScrollDownDesc, prefs::scrollDownKey),
            Slot(Strings.slotScrollLeftTitle, Strings.slotScrollLeftDesc, prefs::scrollLeftKey),
            Slot(Strings.slotScrollRightTitle, Strings.slotScrollRightDesc, prefs::scrollRightKey)
        )
    }

    private val stateListener: () -> Unit = { render() }

    // The accessibility status is verified against the system; refresh it when the setting changes
    // (a service can be turned off without any callback reaching the app).
    private val a11ySettingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            window?.decorView?.postDelayed({ render() }, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.lang = prefs.language
        setContent { DpadTheme { Root() } }
        if (DebugTools.ENABLED) DebugTools.requestStorageAccess(this)
    }

    override fun onStart() {
        super.onStart()
        MouseHub.addListener(stateListener)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11ySettingObserver
        )
        if (DebugTools.ENABLED) DLog.listener = { runOnUiThread { render() } }
        render()
        Updater.check(prefs) { if (!isFinishing && !isDestroyed) render() }
    }

    override fun onStop() {
        MouseHub.removeListener(stateListener)
        contentResolver.unregisterContentObserver(a11ySettingObserver)
        if (DebugTools.ENABLED) DLog.listener = null
        super.onStop()
    }

    private fun render() {
        rev++
    }

    // ================================================================== root

    @Composable
    private fun Root() {
        val state = mainState()
        val mainScroll = rememberScrollState()
        val keysScroll = rememberScrollState()
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

        BackHandler(enabled = page != Page.MAIN) { showPage(Page.MAIN) }

        LaunchedEffect(page) {
            withFrameNanos { }
            withFrameNanos { }
            val target = when (page) {
                Page.MAIN -> returnFocus ?: masterFocus
                Page.KEYS -> keysFirstFocus
                Page.LOG -> logPaneFocus
            }
            runCatching { target.requestFocus() }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            // Overscan-safe margins (Android TV guideline: 48dp sides, 27dp top/bottom).
            AnimatedContent(
                targetState = page,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 27.dp),
                transitionSpec = {
                    (fadeIn(animationSpec = fade) + scaleIn(initialScale = 0.96f, animationSpec = spatial)) togetherWith
                        fadeOut(animationSpec = fade)
                },
                label = "page"
            ) { p ->
                when (p) {
                    Page.MAIN -> MainScreen(state, actions, mainScroll, masterFocus, keysFocus, logFocus)
                    Page.KEYS -> KeysScreen(
                        slots = slots.map { KeySlotUi(it.title, it.desc, keyName(it.prop.get())) },
                        scroll = keysScroll,
                        firstFocus = keysFirstFocus,
                        onBack = { showPage(Page.MAIN) },
                        onLearn = { dialog = Dlg.Learn(slots[it]) },
                        onClear = { i ->
                            val s = slots[i]
                            s.prop.set(KeyEvent.KEYCODE_UNKNOWN)
                            render()
                            toast(Strings.keyClearedToast(s.title))
                        },
                        onReset = {
                            prefs.resetKeys()
                            MouseHub.refreshGrabKeys()
                            render()
                        }
                    )
                    Page.LOG -> LogScreen(
                        lines = DLog.snapshot(logFilter).map { LogLine(it.level, it.text) },
                        filter = logFilter,
                        paneFocus = logPaneFocus,
                        onBack = { showPage(Page.MAIN) },
                        onSave = {
                            DebugTools.saveLog(this@MainActivity, DLog.dumpAll()) { msg ->
                                toast(if (msg.startsWith("Error")) msg else Strings.logSavedPrefix + msg)
                            }
                        },
                        onCopy = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("DpadMouse log", DLog.dumpAll()))
                            toast(Strings.logCopiedToast)
                        },
                        onClear = { DLog.clear() },
                        onFilter = { logFilter = it }
                    )
                }
            }
        }

        Dialogs()
    }

    @Composable
    private fun Dialogs() {
        val d = dialog
        // The accessibility service releases every key while learning so the dialog can receive it.
        if (d is Dlg.Learn || d is Dlg.WhitelistLearn) {
            DisposableEffect(d) {
                MouseHub.learningKeys = true
                onDispose {
                    MouseHub.learningKeys = false
                    MouseHub.refreshGrabKeys()
                }
            }
        }
        when (d) {
            null -> Unit
            Dlg.Language -> {
                val langs = Strings.languages
                LanguageDialog(
                    title = Strings.languageRowName,
                    names = langs.map { it.name },
                    selected = langs.indexOfFirst { it.code == prefs.language }.coerceAtLeast(0),
                    cancelText = Strings.cancelBtn,
                    onPick = { i ->
                        dialog = null
                        val code = langs[i].code
                        if (code != prefs.language) {
                            prefs.language = code
                            recreate()
                        }
                    },
                    onDismiss = { dialog = null }
                )
            }
            is Dlg.Input -> InputDialog(
                title = d.title,
                initial = d.initial,
                numeric = d.numeric,
                okText = Strings.okBtn,
                cancelText = Strings.cancelBtn,
                onOk = { v ->
                    dialog = null
                    d.onOk(v)
                },
                onDismiss = { dialog = null }
            )
            is Dlg.Learn -> LearnDialog(
                title = d.slot.title,
                message = Strings.learnMessage,
                buttonText = Strings.dontUseKeyBtn,
                onButton = {
                    d.slot.prop.set(KeyEvent.KEYCODE_UNKNOWN)
                    dialog = null
                    render()
                },
                onDismiss = { dialog = null },
                onKey = { ev -> learnKey(d.slot, ev) }
            )
            Dlg.Whitelist -> {
                val entries = prefs.whitelist().entries.sortedBy { it.value }
                WhitelistDialog(
                    title = Strings.whitelistRowName,
                    entries = entries.map { it.key to keyName(it.value) },
                    addText = Strings.whitelistAddBtn,
                    closeText = Strings.closeBtn,
                    onRemove = { scan ->
                        prefs.removeWhitelist(scan)
                        MouseHub.refreshGrabKeys()
                        render()
                    },
                    onAdd = { dialog = Dlg.WhitelistLearn },
                    onClose = { dialog = null }
                )
            }
            Dlg.WhitelistLearn -> LearnDialog(
                title = Strings.whitelistRowName,
                message = Strings.learnMessage,
                buttonText = Strings.cancelBtn,
                onButton = { dialog = Dlg.Whitelist },
                onDismiss = { dialog = Dlg.Whitelist },
                onKey = { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) {
                        prefs.addWhitelist(ev.nativeKeyEvent.scanCode, ev.nativeKeyEvent.keyCode)
                        dialog = Dlg.Whitelist
                        render()
                    }
                }
            )
        }
    }

    // ================================================================== state + actions

    private fun mainState(): MainState {
        rev // subscribe: recompose when MouseHub/Prefs change
        val st = MouseHub.state
        val on = MouseHub.mouseModeOn
        val shell = when (st) {
            MouseHub.State.DISCONNECTED -> Strings.statusDisconnected
            MouseHub.State.CONNECTING -> Strings.statusConnecting
            MouseHub.State.CONNECTED -> Strings.statusConnected
        }
        val rel = Updater.pending(prefs)
        return MainState(
            on = on,
            connecting = st == MouseHub.State.CONNECTING,
            masterDesc = when {
                on -> Strings.masterDescOn
                st == MouseHub.State.CONNECTED -> Strings.masterDescConnectedOff
                st == MouseHub.State.CONNECTING -> Strings.masterDescConnecting
                else -> Strings.masterDescDefault
            },
            subtitle = if (on) Strings.subtitleOn else Strings.subtitleOff,
            statusDesc = shell + (MouseHub.lastError?.let { Strings.errorSuffix(it) } ?: ""),
            host = prefs.host,
            port = prefs.port.toString(),
            sensitivity = prefs.sensitivity,
            sensDesc = Strings.sensitivityDesc(prefs.sensitivity),
            toggleDesc = Strings.toggleDesc(keyName(prefs.toggleKey)),
            whitelistDesc = Strings.whitelistDesc(prefs.whitelist().size),
            grabOn = prefs.grabOnly,
            grabDesc = when {
                !prefs.grabOnly -> Strings.grabOff
                MouseHub.grab != null -> Strings.grabRunning
                else -> Strings.grabNotRunning
            },
            a11yDesc = when {
                prefs.grabOnly -> Strings.a11yNotUsed
                MouseHub.serviceActive() -> Strings.a11yRunning
                else -> Strings.a11yNotEnabled
            },
            updateDesc = rel?.let { Strings.updateMessage(it.version) },
            storageDesc = if (DebugTools.ENABLED) {
                if (DebugTools.hasStorageAccess(this)) Strings.storageGranted else Strings.storageNotGranted
            } else null
        )
    }

    private val actions by lazy {
        MainActions(
            onMaster = ::onMasterClick,
            onLanguage = { dialog = Dlg.Language },
            onUpdate = {
                Updater.pending(prefs)?.let { rel ->
                    toast(Strings.updateDownloading)
                    Updater.install(this, rel) { toast(Strings.updateFailed(it)) }
                }
            },
            onHost = {
                dialog = Dlg.Input(Strings.addressRowName, prefs.host, numeric = false) { v ->
                    prefs.host = v.ifEmpty { "127.0.0.1" }
                    render()
                }
            },
            onPort = {
                dialog = Dlg.Input(Strings.portRowName, prefs.port.toString(), numeric = true) { v ->
                    val p = v.toIntOrNull()
                    if (p != null && p in 1..65535) {
                        prefs.port = p
                        render()
                    } else {
                        toast(Strings.invalidPortToast)
                    }
                }
            },
            onConnect = { MouseHub.connect() },
            onDisconnect = { MouseHub.disconnect() },
            onSensSet = { prefs.sensitivity = it; render() },
            onSensStep = { prefs.sensitivity = prefs.sensitivity + it; render() },
            onKeys = { openPage(Page.KEYS, keysFocus) },
            onWhitelist = { dialog = Dlg.Whitelist },
            onGrab = { MouseHub.setGrabOnly(!prefs.grabOnly) },
            onA11y = ::retryAccessibility,
            onOpenLog = { openPage(Page.LOG, logFocus) },
            onNudgeRight = { MouseHub.engine?.nudge(120, 0) ?: toast(Strings.notConnectedToast) },
            onNudgeDown = { MouseHub.engine?.nudge(0, 120) ?: toast(Strings.notConnectedToast) },
            onTestClick = { MouseHub.engine?.click() ?: toast(Strings.notConnectedToast) },
            onDiagnose = ::diagnose,
            onStorage = { DebugTools.requestStorageAccess(this) }
        )
    }

    // ================================================================== logic

    private fun showPage(p: Page) {
        page = p
    }

    private fun openPage(p: Page, from: FocusRequester) {
        returnFocus = from
        showPage(p)
    }

    private fun retryAccessibility() {
        if (MouseHub.keysReady || prefs.grabOnly) return
        toast(Strings.a11yEnablingToast)
        MouseHub.enableAccessibilityService { r ->
            r.onFailure { toast("${Strings.diagnoseErrorPrefix}${it.message}") }
        }
    }

    private fun onMasterClick() {
        when {
            MouseHub.state == MouseHub.State.CONNECTING -> Unit
            // ON but the accessibility service is dead: repair it instead of switching OFF.
            MouseHub.mouseModeOn && !MouseHub.keysReady -> {
                toast(Strings.a11yEnablingToast)
                MouseHub.setMouseMode(true)
            }
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

    /** Stores the pressed key in [slot]. Called for every key except Back while the learn dialog is open. */
    private fun learnKey(slot: Slot, ev: ComposeKeyEvent) {
        val n = ev.nativeKeyEvent
        if (ev.type != KeyEventType.KeyDown || n.repeatCount != 0) return
        if (prefs.grabOnly && ScanMap.isProtected(prefs, n.scanCode, n.keyCode)) {
            toast(Strings.keyProtectedToast(keyName(n.keyCode)))
            return
        }
        slot.prop.set(n.keyCode)
        prefs.learnScan(n.scanCode, n.keyCode)
        // The remote this key came from is the only device EVIOCGRAB may grab.
        n.device?.name?.removePrefix("dpadmouse-")?.let { prefs.addGrabDevice(it) }
        dialog = null
        render()
    }

    private fun keyName(code: Int): String =
        if (code == KeyEvent.KEYCODE_UNKNOWN) Strings.notUsedKeyName
        else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
