package com.agenciapeluda.kutty.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val R12 = RoundedCornerShape(12.dp)
val R14 = RoundedCornerShape(14.dp)

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, kind: String = "primary", small: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val (bg, fg) = when (kind) {
        "ghost" -> C.raised to C.text
        "danger" -> C.danger.copy(alpha = 0.14f) to C.danger
        "stop" -> C.raised to C.danger
        else -> C.accent to Color.White
    }
    Box(
        modifier
            .heightIn(min = if (small) 36.dp else 48.dp)
            .clip(RoundedCornerShape(if (small) 10.dp else 12.dp))
            .background(bg)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (small) 13.5.sp else 15.sp, color = fg), maxLines = 1) }
}

@Composable
fun Chip(text: String, on: Boolean = false, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (on) C.accentDark else C.raised)
            .border(1.dp, if (on) C.accent else Color.Transparent, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontFamily = if (mono) Mono else Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (on) C.text else C.text2), maxLines = 1) }
}

@Composable
fun Tag(text: String, color: Color = C.text2, bg: Color = C.raised) {
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = T.small.copy(color = color, fontSize = 12.sp), maxLines = 1)
    }
}

/** Selector segmentado. */
@Composable
fun <V> Seg(options: List<Pair<V, String>>, value: V, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (V) -> Unit) {
    Row(
        modifier.clip(R12).background(C.dark).border(1.dp, C.divider, R12).padding(3.dp).alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (v, l) ->
            val on = v == value
            Box(
                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(9.dp)).background(if (on) C.raised else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, C.border, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable(enabled = enabled) { onChange(v) }.padding(horizontal = 11.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (on) C.text else C.text2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier.clip(R14).background(C.panel).border(1.dp, C.divider, R14).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}

@Composable
fun H2(text: String) { Text(text.uppercase(), style = T.h2, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)) }

@Composable
fun ScrollRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun Stat(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = T.small)
            Text(value, style = T.num.copy(fontSize = 22.sp))
            if (sub != null) Text(sub, style = T.tiny)
        }
    }
}

val R18 = RoundedCornerShape(18.dp)

/** Cruz de cerrar. */
@Composable
fun CloseX(onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            val w = 2.2f * density
            drawLine(C.text2, Offset(0f, 0f), Offset(size.width, size.height), w, StrokeCap.Round)
            drawLine(C.text2, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
        }
    }
}

/** Barra de título de un modal: título, subtítulo opcional y X. */
@Composable
fun ModalBar(title: String, sub: String? = null, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (sub != null) Text(sub, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = T.h1.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CloseX(onClose)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
}

/**
 * Todo modal: barra con título y X; cierra también tocando afuera o con volver.
 */
@Composable
fun Modal(title: String, onClose: () -> Unit, sub: String? = null, maxWidth: Dp = 720.dp, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val none = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize().clickable(none, null, onClick = onClose).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(R18).background(C.panel).border(1.dp, C.border, R18).clickable(none, null) {}) {
                ModalBar(title, sub, onClose)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp), content = content)
            }
        }
    }
}
