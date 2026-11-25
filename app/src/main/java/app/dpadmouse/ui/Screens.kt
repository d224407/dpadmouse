@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.dpadmouse.ui

import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dpadmouse.DebugTools
import app.dpadmouse.R
import app.dpadmouse.Strings
import kotlinx.coroutines.launch

// ====================================================================== main page

class MainState(
    val on: Boolean,
    val connecting: Boolean,
    val masterDesc: String,
    val subtitle: String,
    val statusDesc: String,
    val host: String,
    val port: String,
    val sensitivity: Int,
    val sensDesc: String,
    val toggleDesc: String,
    val whitelistDesc: String,
    val grabOn: Boolean,
    val grabDesc: String,
    val a11yDesc: String,
    val updateDesc: String?,
    val storageDesc: String?
)

class MainActions(
    val onMaster: () -> Unit,
    val onLanguage: () -> Unit,
    val onUpdate: () -> Unit,
    val onHost: () -> Unit,
    val onPort: () -> Unit,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onSensSet: (Int) -> Unit,
    val onSensStep: (Int) -> Unit,
    val onKeys: () -> Unit,
    val onWhitelist: () -> Unit,
    val onGrab: () -> Unit,
    val onA11y: () -> Unit,
    val onOpenLog: () -> Unit,
    val onNudgeRight: () -> Unit,
    val onNudgeDown: () -> Unit,
    val onTestClick: () -> Unit,
    val onDiagnose: () -> Unit,
    val onStorage: () -> Unit
)

@Composable
private fun SwitchTrailing(checked: Boolean) {
    // The row handles input; the switch only shows state.
    Switch(
        checked = checked,
        onCheckedChange = null,
        thumbContent = if (checked) {
            { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(SwitchDefaults.IconSize)) }
        } else null
    )
}

@Composable
fun MainScreen(
    st: MainState,
    act: MainActions,
    scroll: ScrollState,
    masterFocus: FocusRequester,
    keysFocus: FocusRequester,
    logFocus: FocusRequester
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Strings.appTitle, color = cs.onSurface, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(st.subtitle, color = cs.onSurfaceVariant, fontSize = 17.sp, modifier = Modifier.padding(top = 2.dp))
            }
            ExpressiveButton(Strings.languageButtonLabel, act.onLanguage)
        }

        if (st.updateDesc != null) {
            Column(Modifier.padding(top = 20.dp)) {
                SettingsGroup({ c ->
                    SettingRow(Strings.updateTitle, c, desc = st.updateDesc, onClick = act.onUpdate,
                        trailing = { ValueChip(Strings.updateBtn) })
                })
            }
        }

        SectionTitle(Strings.sectionVirtualMouse)
        SettingsGroup({ c ->
            SettingRow(Strings.mouseModeRow, c, desc = st.masterDesc, active = st.on, focusRequester = masterFocus,
                onClick = act.onMaster, trailing = { SwitchTrailing(st.on) })
        })

        SectionTitle(Strings.sectionLocalAdb)
        SettingsGroup(
            { c ->
                SettingRow(Strings.statusRowName, c, desc = st.statusDesc,
                    trailing = if (st.connecting) {
                        { LoadingIndicator(Modifier.size(44.dp)) }
                    } else null)
            },
            { c -> SettingRow(Strings.addressRowName, c, desc = Strings.addressRowDesc, onClick = act.onHost, trailing = { ValueChip(st.host) }) },
            { c -> SettingRow(Strings.portRowName, c, desc = Strings.portRowDesc, onClick = act.onPort, trailing = { ValueChip(st.port) }) }
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ExpressiveButton(Strings.connectBtn, act.onConnect, Modifier.weight(1f), primary = true)
            ExpressiveButton(Strings.disconnectBtn, act.onDisconnect, Modifier.weight(1f))
        }

        SectionTitle(Strings.sectionControl)
        SettingsGroup(
            { c ->
                // Left/Right on the focused row adjust the value; holding the key speeds it up.
                SettingRow(
                    Strings.sensitivityRowName, c, desc = st.sensDesc,
                    onKey = { ev ->
                        val k = ev.nativeKeyEvent.keyCode
                        if (k == AKeyEvent.KEYCODE_DPAD_LEFT || k == AKeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (ev.type == KeyEventType.KeyDown) {
                                val rc = ev.nativeKeyEvent.repeatCount
                                val step = when {
                                    rc < 8 -> 1
                                    rc < 25 -> 3
                                    else -> 6
                                }
                                act.onSensStep(if (k == AKeyEvent.KEYCODE_DPAD_RIGHT) step else -step)
                            }
                            true
                        } else false
                    },
                    below = { ExpressiveSlider(st.sensitivity, 1, 100, act.onSensSet) }
                )
            },
            { c ->
                SettingRow(Strings.mapKeysRowName, c, desc = st.toggleDesc, focusRequester = keysFocus, onClick = act.onKeys,
                    trailing = { Icon(painterResource(R.drawable.ic_chevron), null, tint = cs.onSurfaceVariant) })
            },
            { c -> SettingRow(Strings.whitelistRowName, c, desc = st.whitelistDesc, onClick = act.onWhitelist) }
        )

        SectionTitle(Strings.sectionDiagnostic)
        SettingsGroup(
            { c -> SettingRow(Strings.grabOnlyRowName, c, desc = st.grabDesc, onClick = act.onGrab, trailing = { SwitchTrailing(st.grabOn) }) },
            { c -> SettingRow(Strings.a11yServiceRowName, c, desc = st.a11yDesc, onClick = act.onA11y) }
        )

        if (DebugTools.ENABLED) {
            SectionTitle(Strings.sectionDebug)
            SettingsGroup(
                { c -> SettingRow(Strings.openLogRowName, c, desc = Strings.openLogRowDesc, focusRequester = logFocus, onClick = act.onOpenLog) },
                { c -> SettingRow(Strings.nudgeRightRowName, c, desc = Strings.nudgeRightRowDesc, onClick = act.onNudgeRight) },
                { c -> SettingRow(Strings.nudgeDownRowName, c, desc = Strings.nudgeDownRowDesc, onClick = act.onNudgeDown) },
                { c -> SettingRow(Strings.testClickRowName, c, desc = Strings.testClickRowDesc, onClick = act.onTestClick) },
                { c -> SettingRow(Strings.diagnoseRowName, c, desc = Strings.diagnoseRowDesc, onClick = act.onDiagnose) },
                { c -> SettingRow(Strings.storageRowName(DebugTools.DIR), c, desc = st.storageDesc, onClick = act.onStorage) }
            )
        }
    }
}

// ====================================================================== "Map keys" page

class KeySlotUi(val title: String, val desc: String, val keyName: String)

@Composable
fun KeysScreen(
    slots: List<KeySlotUi>,
    scroll: ScrollState,
    firstFocus: FocusRequester,
    onBack: () -> Unit,
    onLearn: (Int) -> Unit,
    onClear: (Int) -> Unit,
    onReset: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(Strings.mapKeysTitle, onBack)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
            SettingsGroupOf(slots.mapIndexed<KeySlotUi, @Composable (Corners) -> Unit> { i, s ->
                { c ->
                    // Hold OK on a row to clear its key (the learn dialog captures every other key).
                    SettingRow(s.title, c, desc = s.desc, focusRequester = if (i == 0) firstFocus else null,
                        onClick = { onLearn(i) }, onLongClick = { onClear(i) },
                        trailing = { ValueChip(s.keyName) })
                }
            })
            Text(
                Strings.keysPageNote, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, end = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ExpressiveButton(Strings.resetDefaultsBtn, onReset, Modifier.weight(1f))
            }
        }
    }
}

// ====================================================================== log page (debug)

class LogLine(val level: Char, val text: String)

@Composable
fun LogScreen(
    lines: List<LogLine>,
    filter: Char?,
    paneFocus: FocusRequester,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onFilter: (Char?) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var paneFocused by remember { mutableStateOf(false) }
    val warn = androidx.compose.ui.res.colorResource(R.color.log_warn)
    val error = androidx.compose.ui.res.colorResource(R.color.log_error)

    val text: AnnotatedString = remember(lines, warn, error) {
        buildAnnotatedString {
            for (l in lines) {
                val color = when (l.level) {
                    'E' -> error
                    'W' -> warn
                    else -> null
                }
                if (color != null) withStyle(SpanStyle(color = color)) { append(l.text) } else append(l.text)
                append('\n')
            }
        }
    }
    // Keep the newest line visible.
    LaunchedEffect(lines.size, filter) {
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(Strings.logTitle, onBack) {
            IconBtn(R.drawable.ic_save, onSave)
            IconBtn(R.drawable.ic_copy, onCopy)
            IconBtn(R.drawable.ic_delete, onClear)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf<Pair<Char?, String>>(null to "ALL", 'D' to "DEBUG", 'I' to "INFO", 'W' to "WARN", 'E' to "ERROR")
                .forEach { (level, label) -> FilterPill(label, filter == level, level == 'E') { onFilter(level) } }
        }
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(cs.surfaceContainerHigh, shape)
                .then(if (paneFocused) Modifier.border(3.dp, cs.onSurface, shape) else Modifier)
                // Up/Down scroll the pane; at the ends focus moves on.
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val down = ev.key == Key.DirectionDown
                    val up = ev.key == Key.DirectionUp
                    if ((down && scroll.canScrollForward) || (up && scroll.canScrollBackward)) {
                        scope.launch { scroll.scrollBy(if (down) 90f else -90f) }
                        true
                    } else false
                }
                .focusRequester(paneFocus)
                .onFocusChanged { paneFocused = it.isFocused }
                .focusable()
        ) {
            Text(
                text,
                color = cs.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }
    }
}
