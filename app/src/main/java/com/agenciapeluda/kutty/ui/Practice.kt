package com.agenciapeluda.kutty.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agenciapeluda.kutty.audio.Inputs
import com.agenciapeluda.kutty.audio.REV
import com.agenciapeluda.kutty.audio.UNIT
import com.agenciapeluda.kutty.core.Session
import com.agenciapeluda.kutty.data.Store
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

fun beatLabel(): String {
    val b = Store.curBeat() ?: return "Pista"
    return b.name + " · " + (if (b.loop) "loop ${b.bars}c" else "libre") + " · " + (Math.round(b.bpm * 10) / 10.0) + " BPM"
}

@Composable
fun PracticeScreen() {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { Session.tick(); frame = it } }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    if (Session.tech == null) return
    Box(Modifier.fillMaxSize().background(C.bg).clickable(enabled = false) {}) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val land = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                TopBar(land)
                Hud(land)
                Stage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), frame)
                if (land) Row(Modifier.fillMaxWidth().height(maxHeight * 0.4f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Disc(Modifier.fillMaxHeight().aspectRatio(1f), frame)
                    Fader(Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Disc(Modifier.width(min(maxWidth.value * 0.66f, maxHeight.value * 0.36f).dp).aspectRatio(1f), frame)
                    Fader(Modifier.fillMaxWidth())
                }
            }
        }
        if (!Store.audioReady) Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) { Text("Preparando el audio…", style = T.bodyB) }
        Session.result?.let { Results(it) }
    }
}

@Composable
private fun TopBar(land: Boolean) {
    val t = Session.tech!!
    val run = Session.running
    @Composable fun Title(mod: Modifier) = Column(mod) {
        Text(t.name, style = T.bodyB.copy(fontSize = 15.5.sp), maxLines = 1)
        Text("${t.family} · Nivel ${t.level}", style = T.small.copy(fontSize = 12.sp))
    }
    @Composable fun Controls(mod: Modifier) = Row(mod, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Seg(listOf("aprender" to "Aprender", "practicar" to "Practicar", "desafio" to "Desafío"), Session.mode, Modifier.weight(1f, fill = !land), enabled = !run) { Session.mode = it }
        Row(Modifier.clip(R12).background(C.dark).border(1.dp, C.divider, R12).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !run) { Session.bpm = (Session.bpm - 2).coerceAtLeast(50) }, contentAlignment = Alignment.Center) { Text("−", style = T.body.copy(color = C.text2, fontSize = 18.sp)) }
            Text("${if (run && Session.mode == "desafio") Session.challengeBpm else Session.bpm}", style = T.num.copy(fontSize = 16.sp), modifier = Modifier.widthIn(min = 40.dp))
            Text("BPM", style = T.tiny.copy(fontSize = 10.sp))
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !run) { Session.bpm = (Session.bpm + 2).coerceAtMost(160) }, contentAlignment = Alignment.Center) { Text("+", style = T.body.copy(color = C.text2, fontSize = 18.sp)) }
        }
    }
    val back = @Composable {
        Box(Modifier.size(40.dp).clip(R12).background(C.raised).clickable { Session.stop(true); Session.closeResult(); Nav.practice = false }, contentAlignment = Alignment.Center) { Text("‹", style = T.h1.copy(fontSize = 24.sp)) }
    }
    val go = @Composable {
        Btn(if (run) "Parar" else "Empezar", kind = if (run) "stop" else "primary", enabled = Store.audioReady) { if (run) Session.stop(false) else Session.start(false) }
    }
    Column(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (land) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            back(); Title(Modifier.weight(1f)); Controls(Modifier); go()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { back(); Title(Modifier.weight(1f)); go() }
            Controls(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Hud(land: Boolean) {
    @Composable fun K(label: String, v: String) = Column { Text(label.uppercase(), style = T.tiny.copy(letterSpacing = 0.7.sp)); Text(v, style = T.num.copy(fontSize = 17.sp)) }
    val chips = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AChip(beatLabel(), C.accentText, !Session.running) { Nav.chooser = "beat" }
            AChip(Store.curSound()?.name ?: "Sonido", C.cue, !Session.running) { Nav.chooser = "sound" }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            K("Puntos", "${Session.hudPts}"); K("Combo", "×${Session.hudCombo}"); K("Precisión", if (Session.hudAcc < 0) "—" else "${Session.hudAcc}%")
            if (land) chips()
            Spacer(Modifier.weight(1f))
            Text(Session.hudState, style = if (Session.hudCount) T.num.copy(fontSize = 28.sp) else T.bodyB.copy(fontSize = 14.sp, color = C.accentText), maxLines = 1)
        }
        if (!land) { Spacer(Modifier.height(8.dp)); chips() }
    }
}

@Composable
private fun AChip(text: String, dot: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(C.raised).border(1.dp, C.border, CircleShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp))
        Text(text, style = T.small.copy(fontSize = 12.5.sp, color = if (enabled) C.text2 else C.dim), maxLines = 1)
    }
}

@Composable
private fun Stage(mod: Modifier, frame: Long) {
    val tm = rememberTextMeasurer()
    Canvas(mod.clip(R14).background(C.dark).border(1.dp, C.divider, R14)) {
        @Suppress("UNUSED_VARIABLE") val f = frame
        val now = Session.heardBeat
        val span = if (size.width < 500 * density) 5.0 else 7.0; val lead = 0.32
        val sound = Store.curSound()
        val strip = if (Store.set.waves && sound != null) (if (size.height < 200 * density) 34f else 46f) * density else 0f
        val open = if (Inputs.demo) true else Inputs.faderOpen()
        if (strip > 0 && sound != null) drawScratchStrip(tm, sound.peaks, sound.cue, Inputs.p, open, (size.width * lead).toFloat(), strip, sound.name)
        val live = Session.running || Session.ended
        val beat = Store.curBeat()
        drawTtm(tm, TtmOpts(
            now - span * lead, now + span * (1 - lead), now,
            if (live) Session.timed else emptyList(), Session.samples, Session.flash, live,
            if (Store.set.waves && live && beat != null) beat.peaks else null,
            if (beat != null) { b: Double -> beat.secAt(b) } else null,
        ), strip)
    }
}

@Composable
private fun Disc(mod: Modifier, frame: Long) {
    Canvas(mod.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (Inputs.discActive != "touch") return@awaitEachGesture
            Inputs.touchDisc(true, down.uptimeMillis)
            val c = Offset(size.width / 2f, size.height / 2f)
            var last = down.position - c
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                val pts = ch.historical.map { it.position to it.uptimeMillis } + (ch.position to ch.uptimeMillis)
                for ((pos, tms) in pts) {
                    val pt = pos - c
                    val dp = if (Store.set.platter == "lineal") (last.y - pt.y) / (size.height * 0.45) * Store.set.sens
                    else {
                        val pi = PI.toFloat()
                        var da = atan2(pt.y, pt.x) - atan2(last.y, last.x)
                        if (da > pi) da -= 2 * pi
                        if (da < -pi) da += 2 * pi
                        if (hypot(pt.x, pt.y) < size.width * 0.06f) da = 0f
                        da / (2 * PI) * (REV / UNIT) * Store.set.sens
                    }
                    last = pt
                    Inputs.moveDisc(dp, tms)
                }
                ch.consume()
            }
            Inputs.touchDisc(false, android.os.SystemClock.uptimeMillis())
        }
    }) {
        @Suppress("UNUSED_VARIABLE") val f = frame
        drawDisc(Inputs.p, if (Inputs.demo) true else Inputs.faderOpen())
    }
}

@Composable
private fun Fader(mod: Modifier) {
    val pad = Store.set.faderMode == "pad"
    var tick by remember { mutableLongStateOf(0L) }
    Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(80.dp).clip(R14).background(C.panel).border(1.dp, C.border, R14).pointerInput(pad) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (pad) Inputs.setPad(true) else Inputs.setFaderPos(down.position.x / size.width)
                tick++
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (!pad) { ch.historical.forEach { Inputs.setFaderPos(it.position.x / size.width) }; Inputs.setFaderPos(ch.position.x / size.width) }
                    ch.consume(); tick++
                }
                if (pad) Inputs.setPad(false)
                tick++
            }
        }) {
            @Suppress("UNUSED_VARIABLE") val tk = tick
            val open = Inputs.faderOpen()
            if (pad) {
                drawRect(if (open) C.accentDark else C.panel)
                return@Canvas
            }
            val y = size.height / 2
            drawRoundRect(C.divider, Offset(14 * density, y - 2 * density), Size(size.width - 28 * density, 4 * density))
            val cutX = (if (Store.set.hamster) 1 - Store.set.cutIn else Store.set.cutIn) * size.width
            drawRect(C.tick, Offset(cutX - density, 18 * density), Size(2 * density, size.height - 36 * density))
            val kx = Inputs.fv * size.width
            drawRoundRect(if (open) C.accent else C.raised, Offset(kx - 15 * density, 12 * density), Size(30 * density, size.height - 24 * density), androidx.compose.ui.geometry.CornerRadius(8 * density))
        }
        val src = Inputs.faderActive
        Text(
            when { src == "midi" -> "Fader de la controladora"; src == "dvs" -> "Fader: corte del timecode"; pad -> "Mantené para abrir el fader"; Store.set.hamster -> "abierto ← · → cerrado"; else -> "cerrado ← · → abierto" },
            style = T.small.copy(color = C.dim), modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Results(r: com.agenciapeluda.kutty.core.Result) {
    val tm = rememberTextMeasurer()
    val rec = r.rec
    Dialog(onDismissRequest = { Session.closeResult() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(18.dp)).background(C.panel).border(1.dp, C.border, RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("${modeName(rec.mode)} · ${rec.bpm} BPM", style = T.small)
                Text(Session.tech?.name ?: "", style = T.h1)
                Spacer(Modifier.height(14.dp))
                Row {
                    listOf(Triple("${rec.acc}%", "precisión", C.acc(rec.acc)), Triple("${rec.pts}", "puntos", C.text), Triple("×${rec.combo}", "mejor combo", C.text)).forEach { (v, l, col) ->
                        Column(Modifier.weight(1f)) { Text(v, style = T.num.copy(fontSize = 28.sp, color = col)); Text(l, style = T.small) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(C.divider), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    rec.counts.forEachIndexed { i, n -> if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(C.grade[i])) }
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rec.counts.forEachIndexed { i, n -> Text("${C.gradeName[i]} $n", style = T.small.copy(color = C.grade[i])) }
                }
                Canvas(Modifier.fillMaxWidth().height(120.dp).clip(R12).background(C.dark)) {
                    drawTtm(tm, TtmOpts(-0.3, min(r.total, 32.0) + 0.3, null, r.timed, Session.samples))
                }
                Spacer(Modifier.height(12.dp))
                r.feedback.forEach { (lv, txt) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(R12).background(C.raised)) {
                        Box(Modifier.width(3.dp).height(40.dp).background(when (lv) { 0 -> C.ok; 1 -> C.cue; else -> C.danger }))
                        Text(txt, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cerrar", Modifier.weight(1f), "ghost") { Session.closeResult() }
                    Btn("Repetir", Modifier.weight(1f)) { Session.closeResult(); Session.start(false) }
                }
            }
        }
    }
}
