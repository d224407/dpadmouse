@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.dpadmouse.ui

import android.view.KeyEvent as AKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Top/bottom corner radii of a row inside a group (big outside, small between rows). */
class Corners(val top: Dp, val bottom: Dp) {
    companion object {
        val Big = 28.dp
        val Small = 6.dp
        val Pill = Corners(Big, Big)
        fun at(i: Int, n: Int) = when {
            n == 1 -> Pill
            i == 0 -> Corners(Big, Small)
            i == n - 1 -> Corners(Small, Big)
            else -> Corners(Small, Small)
        }
    }
}

@Composable
fun SettingsGroup(vararg items: @Composable (Corners) -> Unit) = SettingsGroupOf(items.toList())

@Composable
fun SettingsGroupOf(items: List<@Composable (Corners) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { i, item -> item(Corners.at(i, items.size)) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.64.sp,
        modifier = Modifier.padding(start = 8.dp, top = 28.dp, end = 8.dp, bottom = 10.dp)
    )
}

/**
 * Focusable surface for D-pad use: scales up and morphs its corners while focused, with a bright ring.
 * OK/Enter = click, holding OK = long click; pointer taps work too.
 */
@Composable
fun FocusCard(
    corners: Corners,
    container: Color,
    focusedContainer: Color,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusable: Boolean = true,
    scale: Float = 1.02f,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val motion = MaterialTheme.motionScheme
    val s by animateFloatAsState(if (focused) scale else 1f, motion.fastSpatialSpec(), label = "scale")
    val top by animateDpAsState(if (focused) Corners.Big else corners.top, motion.fastSpatialSpec(), label = "top")
    val bottom by animateDpAsState(if (focused) Corners.Big else corners.bottom, motion.fastSpatialSpec(), label = "bottom")
    val bg by animateColorAsState(if (focused) focusedContainer else container, motion.fastEffectsSpec(), label = "bg")
    val t = top.coerceAtLeast(0.dp)
    val b = bottom.coerceAtLeast(0.dp)
    val shape = RoundedCornerShape(t, t, b, b)

    val clickRef = rememberUpdatedState(onClick)
    val longRef = rememberUpdatedState(onLongClick)
    val keyRef = rememberUpdatedState(onKey)
    var longFired by remember { mutableStateOf(false) }

    var m = modifier
        .zIndex(if (focused) 1f else 0f) // draw above neighbours while enlarged
        .graphicsLayer { scaleX = s; scaleY = s }
        .background(bg, shape)
    if (focused) m = m.border(3.dp, MaterialTheme.colorScheme.onSurface, shape)
    if (focusable) {
        m = m.onPreviewKeyEvent { ev ->
            if (keyRef.value?.invoke(ev) == true) return@onPreviewKeyEvent true
            val n = ev.nativeKeyEvent
            val ok = n.keyCode == AKeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == AKeyEvent.KEYCODE_ENTER ||
                n.keyCode == AKeyEvent.KEYCODE_NUMPAD_ENTER
            if (!ok || clickRef.value == null) return@onPreviewKeyEvent false
            when (ev.type) {
                KeyEventType.KeyDown -> {
                    if (n.repeatCount == 0) {
                        longFired = false
                    } else if (!longFired && longRef.value != null && n.eventTime - n.downTime >= 500) {
                        longFired = true
                        longRef.value?.invoke()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (!longFired) clickRef.value?.invoke()
                    longFired = false
                    true
                }
                else -> false
            }
        }
        if (onClick != null) {
            m = m.pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { longRef.value?.invoke() },
                    onTap = { clickRef.value?.invoke() }
                )
            }
        }
        if (focusRequester != null) m = m.focusRequester(focusRequester)
        m = m.focusable(interactionSource = source)
    }
    Box(m, content = content)
}

/** A settings row: title + optional description, optional trailing content, optional full-width content below. */
@Composable
fun SettingRow(
    title: String,
    corners: Corners,
    modifier: Modifier = Modifier,
    desc: String? = null,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val fg = if (active) cs.onPrimaryContainer else cs.onSurface
    val fgVariant = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant
    FocusCard(
        corners = corners,
        container = if (active) cs.primaryContainer else cs.surfaceContainerHigh,
        focusedContainer = if (active) cs.primaryContainer else cs.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        focusable = onClick != null || onKey != null,
        onClick = onClick,
        onLongClick = onLongClick,
        onKey = onKey
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 28.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = fg, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                    if (!desc.isNullOrEmpty()) {
                        Text(desc, color = fgVariant, fontSize = 16.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(20.dp))
                    trailing()
                }
            }
            if (below != null) {
                Spacer(Modifier.height(14.dp))
                below()
            }
        }
    }
}

/** Small value chip on the right of a row (mapped key, address, ...). */
@Composable
fun ValueChip(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.background(cs.secondaryContainer, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(text, color = cs.onSecondaryContainer, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** Filled / tonal button whose shape morphs when pressed and that scales up with a ring when focused. */
@Composable
fun ExpressiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val radius by animateDpAsState(if (pressed) 12.dp else 28.dp, motion.fastSpatialSpec(), label = "radius")
    val s by animateFloatAsState(if (focused) 1.05f else 1f, motion.fastSpatialSpec(), label = "scale")
    val shape = RoundedCornerShape(radius.coerceAtLeast(0.dp))
    var m = modifier
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = s; scaleY = s }
        .heightIn(min = 56.dp)
    if (focused) m = m.border(3.dp, MaterialTheme.colorScheme.onSurface, shape)
    val padding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
    val label: @Composable RowScope.() -> Unit = { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
    if (primary) {
        Button(onClick = onClick, modifier = m, shape = shape, contentPadding = padding, interactionSource = source, content = label)
    } else {
        FilledTonalButton(onClick = onClick, modifier = m, shape = shape, contentPadding = padding, interactionSource = source, content = label)
    }
}

@Composable
fun IconBtn(iconRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    FocusCard(
        corners = Corners.Pill,
        container = cs.surfaceContainerHigh,
        focusedContainer = cs.surfaceContainerHighest,
        modifier = modifier.size(56.dp),
        scale = 1.08f,
        onClick = onClick
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = cs.onSurface, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun FilterPill(text: String, selected: Boolean, error: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fill = when {
        selected && error -> cs.errorContainer
        selected -> cs.primary
        else -> cs.surfaceContainerHigh
    }
    val fg = when {
        selected && error -> cs.onErrorContainer
        selected -> cs.onPrimary
        else -> cs.onSurfaceVariant
    }
    FocusCard(
        corners = Corners(24.dp, 24.dp),
        container = fill,
        focusedContainer = if (selected) fill else cs.surfaceContainerHighest,
        scale = 1.05f,
        onClick = onClick
    ) {
        Text(
            text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.32.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

/**
 * Expressive slider (thick track, gap around a vertical handle). Not focusable: the focused row adjusts it
 * with Left/Right; tapping or dragging with a pointer sets it directly.
 */
@Composable
fun ExpressiveSlider(value: Int, min: Int, max: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = cs.primary
    val inactive = cs.outlineVariant
    val current = rememberUpdatedState(onChange)
    val animated by animateFloatAsState(
        (value - min).toFloat() / (max - min), MaterialTheme.motionScheme.fastSpatialSpec(), label = "slider"
    )

    fun set(x: Float, w: Int, pad: Float) {
        val frac = ((x - pad) / (w - 2 * pad)).coerceIn(0f, 1f)
        val v = min + (frac * (max - min)).roundToInt()
        current.value(v)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures { set(it.x, size.width, 14.dp.toPx()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    set(change.position.x, size.width, 14.dp.toPx())
                }
            }
            .drawBehind {
                val pad = 14.dp.toPx()
                val trackH = 16.dp.toPx()
                val top = (size.height - trackH) / 2
                val x = pad + animated * (size.width - 2 * pad)
                val gap = 6.dp.toPx()
                val r = CornerRadius(trackH / 2)
                if (x - gap > 0f) {
                    drawRoundRect(active, Offset(0f, top), Size(x - gap, trackH), r)
                }
                if (x + gap < size.width) {
                    drawRoundRect(inactive, Offset(x + gap, top), Size(size.width - x - gap, trackH), r)
                }
                val handleW = 6.dp.toPx()
                drawRoundRect(
                    active, Offset(x - handleW / 2, 0f), Size(handleW, size.height), CornerRadius(handleW / 2)
                )
            }
    )
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(app.dpadmouse.R.drawable.ic_back, onBack)
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 20.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = trailing)
    }
}
