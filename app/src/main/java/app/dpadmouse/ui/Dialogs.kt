@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.dpadmouse.ui

import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dpadmouse.R

private suspend fun FocusRequester.focusWhenReady() {
    withFrameNanos { }
    withFrameNanos { }
    runCatching { requestFocus() }
}

@Composable
fun LanguageDialog(
    title: String,
    names: List<String>,
    selected: Int,
    cancelText: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                names.forEachIndexed { i, name ->
                    SettingRow(
                        title = name,
                        corners = Corners.at(i, names.size),
                        active = i == selected,
                        focusRequester = if (i == selected) first else null,
                        onClick = { onPick(i) },
                        trailing = if (i == selected) {
                            { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(24.dp)) }
                        } else null
                    )
                }
            }
        },
        confirmButton = { ExpressiveButton(cancelText, onDismiss) }
    )
}

@Composable
fun InputDialog(
    title: String,
    initial: String,
    numeric: Boolean,
    okText: String,
    cancelText: String,
    onOk: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 22.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onOk(field.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { ExpressiveButton(okText, { onOk(field.text.trim()) }, primary = true) },
        dismissButton = { ExpressiveButton(cancelText, onDismiss) }
    )
}

/** Captures the next key press from the controller: every key except Back goes to [onKey]. */
@Composable
fun LearnDialog(
    title: String,
    message: String,
    buttonText: String,
    onButton: () -> Unit,
    onDismiss: () -> Unit,
    onKey: (KeyEvent) -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .onPreviewKeyEvent { ev ->
                        if (ev.nativeKeyEvent.keyCode == AKeyEvent.KEYCODE_BACK) {
                            false
                        } else {
                            onKey(ev)
                            true
                        }
                    }
                    .focusRequester(focus)
                    .focusable()
            ) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { ExpressiveButton(buttonText, onButton) }
    )
}

@Composable
fun WhitelistDialog(
    title: String,
    entries: List<Pair<Int, String>>,
    addText: String,
    closeText: String,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (entries.isNotEmpty()) first.focusWhenReady() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                entries.forEachIndexed { i, (scan, label) ->
                    SettingRow(
                        title = label,
                        corners = Corners.at(i, entries.size),
                        focusRequester = if (i == 0) first else null,
                        onClick = { onRemove(scan) }
                    )
                }
            }
        },
        confirmButton = { ExpressiveButton(addText, onAdd, primary = true) },
        dismissButton = { ExpressiveButton(closeText, onClose) }
    )
}
