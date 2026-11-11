@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import com.agenciapeluda.kutty.audio.Engine
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
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
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign

fun beatLabel(): String {
    val b = Store.curBeat() ?: return "Pista"
    return b.name + " · " + (if (b.loop) "loop ${b.bars}c" else "libre") + " · " + (Math.round(b.bpm * 10) / 10.0) + " BPM"
}

@Composable
fun PracticeScreen() {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { Session.tick(); Recorder.poll(); frame = it } }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false; Inputs.phoneMode = false } }
    if (Session.tech == null) return
    Box(Modifier.fillMaxSize().background(C.bg).clickable(enabled = false) {}) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val mw = maxWidth
            val mh = maxHeight
            val land = mw > mh
            // modo teléfono: horizontal y sin ningún dispositivo conectado
            val noDevices = Inputs.midiDevices.isEmpty() && Inputs.audioInputs.isEmpty() && Inputs.audioOutputs.isEmpty()
            val phone = land && noDevices
            SideEffect { Inputs.phoneMode = phone }
            val ham = Store.set.hamster
            if (phone) PhoneLayout(frame)
            else Column(Modifier.fillMaxSize()) {
                TopBar(land)
                Hud(land)
                Stage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), frame)
                if (land) Row(Modifier.fillMaxWidth().height(mh * 0.4f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // hamster: el plato en pantalla y el fader cambian de lado
                    if (ham) { Fader(Modifier.weight(1f)); Disc(Modifier.fillMaxHeight().aspectRatio(1f), frame) }
                    else { Disc(Modifier.fillMaxHeight().aspectRatio(1f), frame); Fader(Modifier.weight(1f)) }
                } else Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Disc(Modifier.width(min(mw.value * 0.66f, mh.value * 0.36f).dp).aspectRatio(1f), frame)
                    Fader(Modifier.fillMaxWidth())
                }
            }
        }
        if (!Store.audioReady) Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) { Text("Preparando el audio…", style = T.bodyB) }
        Session.result?.let { Results(it) }
        if (Nav.bpmSheet) BpmDialog()
    }
}

/** Horizontal sin controladora: medio vinilo pegado al borde y botón de corte del otro lado. */
@Composable
private fun PhoneLayout(frame: Long) {
    val ham = Store.set.hamster
    Column(Modifier.fillMaxSize()) {
        TopBar(true)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val r = maxHeight * 0.95f
            val dw = minOf(r * 0.65f + 6.dp, maxWidth * 0.48f)
            val disc: @Composable RowScope.() -> Unit = { Disc(Modifier.width(dw).fillMaxHeight(), frame, if (ham) 1 else -1) }
            val side: @Composable RowScope.() -> Unit = {
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stage(Modifier.weight(1f).fillMaxWidth(), frame)
                    MiniHud()
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = if (ham) Alignment.CenterStart else Alignment.CenterEnd) { CutButton(frame) }
                }
            }
            Row(Modifier.fillMaxSize()) { if (ham) { side(); disc() } else { disc(); side() } }
        }
    }
}

@Composable
private fun TopBar(land: Boolean) {
    val t = Session.tech!!
    val run = Session.running
    @Composable fun Title(mod: Modifier) = Column(mod) {
        Text(t.name, style = T.bodyB.copy(fontSize = 15.5.sp), maxLines = 1)
        Text("${t.family} · Nivel ${t.level}", style = T.small.copy(fontSize = 12.sp), maxLines = 1)
    }
    @Composable fun Controls(mod: Modifier) = Row(mod, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Seg(listOf("aprender" to "Aprender", "practicar" to "Practicar", "desafio" to "Desafío"), Session.mode, Modifier.weight(1f, fill = !land), enabled = !run) { Session.mode = it }
        Row(Modifier.clip(R12).background(C.dark).border(1.dp, C.divider, R12).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !run) { Session.bpm = (Session.bpm - 2).coerceAtLeast(50) }, contentAlignment = Alignment.Center) { Text("−", style = T.body.copy(color = C.text2, fontSize = 18.sp)) }
            Text("${if (run && Session.mode == "desafio" && !Session.demoOnly) Session.challengeBpm else Session.bpm}", style = T.num.copy(fontSize = 16.sp), modifier = Modifier.widthIn(min = 40.dp).clickable(enabled = !run) { Nav.bpmSheet = true })
            Text("BPM", style = T.tiny.copy(fontSize = 10.sp))
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !run) { Session.bpm = (Session.bpm + 2).coerceAtMost(160) }, contentAlignment = Alignment.Center) { Text("+", style = T.body.copy(color = C.text2, fontSize = 18.sp)) }
        }
    }
    val back = @Composable {
        Box(Modifier.size(40.dp).clip(R12).background(C.raised).clickable { Session.stop(true); Session.closeResult(); Nav.practice = false }, contentAlignment = Alignment.Center) { Text("‹", style = T.h1.copy(fontSize = 24.sp)) }
    }
    // Escuchar (secundario): demo de dos compases con plato y fader automáticos
    val go = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!run) Btn("Escuchar", kind = "ghost", enabled = Store.audioReady) { Session.start(true) }
            Btn(if (run) "Parar" else "Empezar", kind = if (run) "stop" else "primary", enabled = Store.audioReady) { if (run) Session.stop(false) else Session.start(false) }
        }
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
private fun Chips() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Seg(listOf("completo" to "Completo", "plato" to "Solo plato", "fader" to "Solo fader"), Session.part, enabled = !Session.running) { Session.part = it }
        AChip(beatLabel(), C.accentText, !Session.running) { Nav.chooser = "beat" }
        AChip(Store.curSound()?.name ?: "Sonido", C.cue, !Session.running) { Nav.chooser = "sound" }
        // diagnóstico: graba 10 s de la salida en WAV
        AChip(if (Recorder.recording) "Grabando… ${(Recorder.SECONDS * (1 - Recorder.progress)).toInt() + 1} s" else "Grabar 10 s", C.danger, !Recorder.recording) { Recorder.start() }
    }
}

@Composable
private fun Hud(land: Boolean) {
    @Composable fun K(label: String, v: String) = Column { Text(label.uppercase(), style = T.tiny.copy(letterSpacing = 0.7.sp)); Text(v, style = T.num.copy(fontSize = 17.sp)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            K("Puntos", "${Session.hudPts}"); K("Combo", "×${Session.hudCombo}"); K("Precisión", if (Session.hudAcc < 0) "—" else "${Session.hudAcc}%")
            if (land) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { Chips() } else Spacer(Modifier.weight(1f))
            Text(Session.hudState, style = if (Session.hudCount) T.num.copy(fontSize = 28.sp) else T.bodyB.copy(fontSize = 14.sp, color = C.accentText), maxLines = 1)
        }
        if (!land) { Spacer(Modifier.height(8.dp)); Row(Modifier.horizontalScroll(rememberScrollState())) { Chips() } }
    }
}

/** Modo teléfono: elecciones, beat y contador, chiquitos. */
@Composable
private fun MiniHud() {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { Chips() }
        Text("${Session.hudPts} pts · ×${Session.hudCombo}" + if (Session.hudAcc >= 0) " · ${Session.hudAcc}%" else "", style = T.monoS, maxLines = 1)
        Text(Session.hudState, style = if (Session.hudCount) T.num.copy(fontSize = 18.sp) else T.small.copy(color = C.accentText), maxLines = 1)
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

private fun evNs(ev: MotionEvent): Long = if (Build.VERSION.SDK_INT >= 34) ev.eventTimeNanos else ev.eventTime * 1_000_000L
private fun histNs(ev: MotionEvent, h: Int): Long = if (Build.VERSION.SDK_INT >= 34) ev.getHistoricalEventTimeNanos(h) else ev.getHistoricalEventTime(h) * 1_000_000L

/**
 * Toque del disco con los MotionEvent crudos: cada punto histórico con su tiempo real en ns,
 * una sola tanda al motor por evento. Sigue a un solo dedo (el otro puede estar en el corte).
 * half: 0 disco entero; -1 medio vinilo pegado al borde izquierdo; 1 al derecho.
 */
private class DiscTouch(val half: Int) {
    var id = -1
    var lx = 0f; var ly = 0f
    var w = 0f; var h = 0f; var cx = 0f; var cy = 0f; var r = 0f

    fun layout(width: Float, height: Float, margin: Float) {
        w = width; h = height; cy = h / 2
        when (half) {
            0 -> { cx = w / 2; r = min(w, h) / 2 }
            -1 -> { r = min(h * 0.95f, (w - margin) / 0.65f); cx = w - margin - r }
            else -> { r = min(h * 0.95f, (w - margin) / 0.65f); cx = margin + r }
        }
    }

    fun handle(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (id != -1 || Inputs.discActive != "touch") return
                for (i in 0 until ev.pointerCount) {
                    val x = ev.getX(i); val y = ev.getY(i)
                    if (x < 0f || y < 0f || x > w || y > h) continue
                    id = ev.getPointerId(i); lx = x - cx; ly = y - cy
                    Inputs.touchDisc(true, evNs(ev))
                    return
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (id == -1) return
                val i = ev.findPointerIndex(id)
                if (i < 0) return
                val dps = Inputs.batchDp; val ts = Inputs.batchT
                var n = 0
                val hs = min(ev.historySize, dps.size - 1)
                for (k in 0 until hs) { dps[n] = step(ev.getHistoricalX(i, k), ev.getHistoricalY(i, k)); ts[n] = histNs(ev, k); n++ }
                dps[n] = step(ev.getX(i), ev.getY(i)); ts[n] = evNs(ev); n++
                Inputs.moveDisc(dps, ts, n)
            }
            MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == id) release(evNs(ev))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (id != -1) release(evNs(ev))
        }
    }

    private fun release(t: Long) { id = -1; Inputs.touchDisc(false, t) }

    private fun step(x: Float, y: Float): Double {
        val px = x - cx; val py = y - cy
        val s = Store.set
        val dp = if (s.platter == "lineal") ((ly - py) / (h * 0.45f) * s.sens).toDouble()
        else {
            val pi = PI.toFloat()
            var da = atan2(py, px) - atan2(ly, lx)
            if (da > pi) da -= 2 * pi
            if (da < -pi) da += 2 * pi
            if (hypot(px, py) < r * 0.06f) da = 0f
            da / (2 * PI) * (REV / UNIT) * s.sens
        }
        lx = px; ly = py
        return dp
    }
}

@Composable
private fun Disc(mod: Modifier, frame: Long, half: Int = 0) {
    val t = remember(half) { DiscTouch(half) }
    val margin = with(LocalDensity.current) { 6.dp.toPx() }
    Canvas(mod.clipToBounds().onSizeChanged { t.layout(it.width.toFloat(), it.height.toFloat(), margin) }.pointerInteropFilter { ev -> t.handle(ev); true }) {
        @Suppress("UNUSED_VARIABLE") val f = frame
        val open = if (Inputs.demo) true else Inputs.faderOpen()
        if (half == 0) drawDisc(Inputs.p, open)
        else {
            if (t.w != size.width || t.h != size.height) t.layout(size.width, size.height, margin)
            drawHalfDisc(Inputs.p, open, Offset(t.cx, t.cy), t.r, if (half < 0) 0f else 180f)
        }
    }
}

/** Botón de corte del modo teléfono: actúa al tocar, vibra, independiente del plato. */
@Composable
private fun CutButton(frame: Long) {
    val view = LocalView.current
    val touchable = !Inputs.autoFader && Inputs.faderActive == "touch"
    val hold = Store.set.holdCuts
    Box(Modifier.size(160.dp).pointerInput(touchable) {
        if (!touchable) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            Inputs.setPad(true)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            down.consume()
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                ch.consume()
            }
            Inputs.setPad(false)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(110.dp)) {
            @Suppress("UNUSED_VARIABLE") val f = frame
            val open = Inputs.faderOpen()
            drawCircle(if (open) C.accent else C.raised)
            drawCircle(if (open) C.accentText else C.tick, style = androidx.compose.ui.graphics.drawscope.Stroke(3 * density))
            drawCircle(if (open) Color.White else C.dim, radius = 6 * density)
        }
        Text(if (!touchable) "auto" else if (hold) "mantener\ncorta" else "mantener\nabre", style = T.tiny.copy(fontSize = 10.sp, color = C.text2, textAlign = TextAlign.Center), modifier = Modifier.padding(top = 56.dp))
    }
}

@Composable
private fun Fader(mod: Modifier) {
    val mode = Store.set.faderMode
    val btn = mode != "slide"
    val auto = Inputs.autoFader
    var tick by remember { mutableLongStateOf(0L) }
    Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val touchable = !auto && Inputs.faderActive == "touch"
        val gestures = Modifier.pointerInput(btn, touchable) {
            if (!touchable) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                if (btn) Inputs.setPad(true) else Inputs.setFaderPos(down.position.x / size.width)
                tick++
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (!btn) { ch.historical.forEach { Inputs.setFaderPos(it.position.x / size.width) }; Inputs.setFaderPos(ch.position.x / size.width) }
                    ch.consume(); tick++
                }
                if (btn) Inputs.setPad(false)
                tick++
            }
        }
        if (btn && touchable) {
            // botón redondo para el pulgar
            Canvas(Modifier.size(96.dp).clip(CircleShape).then(gestures)) {
                @Suppress("UNUSED_VARIABLE") val tk = tick
                val open = Inputs.faderOpen()
                drawCircle(if (open) C.accent else C.raised)
                drawCircle(if (open) C.accentText else C.tick, style = androidx.compose.ui.graphics.drawscope.Stroke(3 * density))
                drawCircle(if (open) Color.White else C.dim, radius = 6 * density)
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(80.dp).clip(R14).background(C.panel).border(1.dp, C.border, R14).then(gestures)) {
                @Suppress("UNUSED_VARIABLE") val tk = tick
                @Suppress("UNUSED_VARIABLE") val fr = Session.heardBeat   // redibuja con cada cuadro (MIDI y automático)
                val open = Inputs.faderOpen()
                val y = size.height / 2
                drawRoundRect(C.divider, Offset(14 * density, y - 2 * density), Size(size.width - 28 * density, 4 * density))
                if (Inputs.faderActive == "touch" && !auto && !btn) {
                    val cutX = (if (Store.set.hamster) 1 - Store.set.cutIn else Store.set.cutIn) * size.width
                    drawRect(C.tick, Offset(cutX - density, 18 * density), Size(2 * density, size.height - 36 * density))
                }
                // el fader se mueve aunque lo toque la controladora o la app (posición física)
                val kx = 15 * density + Inputs.faderKnob() * (size.width - 30 * density)
                drawRoundRect(if (open) C.accent else C.raised, Offset(kx - 15 * density, 12 * density), Size(30 * density, size.height - 24 * density), androidx.compose.ui.geometry.CornerRadius(8 * density))
            }
        }
        val src = Inputs.faderActive
        Text(
            when {
                auto -> "Fader automático: mirá cómo se mueve y seguilo con el disco"
                src == "midi" -> "Fader de la controladora"
                src == "dvs" -> "Fader: corte del timecode"
                mode == "pad" -> "Mantené apretado para que suene"
                mode == "cut" -> "Suena solo; cada toque es un click"
                Store.set.hamster -> "abierto ← · → cerrado"
                else -> "cerrado ← · → abierto"
            },
            style = T.small.copy(color = C.dim),
        )
    }
}


@Composable
private fun BpmDialog() {
    val t = Session.tech ?: return
    val beat = Store.curBeat()
    val s = Store.set
    val taps = remember { ArrayList<Long>() }
    fun setB(v: Int) { Session.bpm = v.coerceIn(50, 160) }
    Modal("Tempo del ejercicio", { Nav.bpmSheet = false }, maxWidth = 520.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("−1", kind = "ghost", small = true) { setB(Session.bpm - 1) }
                Text("${Session.bpm}", style = T.num.copy(fontSize = 34.sp), modifier = Modifier.weight(1f))
                Text("BPM", style = T.small)
                Btn("+1", kind = "ghost", small = true) { setB(Session.bpm + 1) }
            }
            KSlider(Session.bpm.toFloat(), 50f..160f, "${Session.bpm}") { setB(it.roundToInt()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Tap", Modifier.weight(1f), "ghost") {
                    val now = System.currentTimeMillis()
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now); while (taps.size > 6) taps.removeAt(0)
                    if (taps.size >= 3) setB((60000.0 / ((taps.last() - taps.first()).toDouble() / (taps.size - 1))).roundToInt())
                }
                Btn("Técnica · ${t.bpm}", Modifier.weight(1f), "ghost") { setB(t.bpm) }
            }
            if (beat != null && !beat.builtin) Btn("A tempo de la pista · ${Math.round(beat.bpm)}", Modifier.fillMaxWidth(), "ghost") { setB(Math.round(beat.bpm).toInt()) }
            Text("Práctica lenta", style = T.h2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val base = if (beat != null && !beat.builtin) Math.round(beat.bpm).toInt() else t.bpm
                listOf(100, 75, 50).forEach { pc -> Btn("$pc %", Modifier.weight(1f), "ghost", small = true) { setB(base * pc / 100) } }
            }
            Text("Progresivo", style = T.h2)
            Text("Cada vez que pasás el ejercicio con 85 % o más en Practicar, sube solo.", style = T.small)
            Seg(listOf(false to "Apagado", true to "Prendido"), s.progressive) { s.progressive = it; Store.save() }
            if (s.progressive) {
                Text("Sube de a ${s.progStep} BPM hasta ${s.progMax}", style = T.small)
                Seg(listOf(1 to "+1", 2 to "+2", 4 to "+4"), s.progStep) { s.progStep = it; Store.save() }
                KSlider(s.progMax.toFloat(), 60f..160f, "${s.progMax}") { s.progMax = it.roundToInt(); Store.save() }
            }
            Text("Tono del beat", style = T.h2)
            Seg(listOf(false to "Como un plato", true to "Keylock"), s.keylock) { s.keylock = it; Store.save(); Engine.nSetKeylock(it) }
            Btn("Listo", Modifier.fillMaxWidth()) { Nav.bpmSheet = false }
        }
    }
}

@Composable
private fun Results(r: com.agenciapeluda.kutty.core.Result) {
    val tm = rememberTextMeasurer()
    val rec = r.rec
    Modal(Session.tech?.name ?: "Resultado", { Session.closeResult() }, sub = "${modeName(rec.mode)} · ${rec.bpm} BPM", maxWidth = 640.dp) {
        Column {
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
