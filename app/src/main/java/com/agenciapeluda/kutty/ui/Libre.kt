package com.agenciapeluda.kutty.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenciapeluda.kutty.audio.Engine
import com.agenciapeluda.kutty.audio.Inputs
import com.agenciapeluda.kutty.audio.UNIT
import com.agenciapeluda.kutty.core.LibreRead
import com.agenciapeluda.kutty.core.Session
import com.agenciapeluda.kutty.data.Store
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Libre: beat, plato, cues 1-4 y ajuste de inicio y fin, sin salir. */
@Composable
fun LibreBar(compact: Boolean) {
    val run = Session.running
    val playing = run && !Session.beatPaused
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn(if (playing) "❚❚  Beat" else "▶  Beat", kind = if (playing) "ghost" else "primary", small = true, enabled = Store.audioReady) { Session.toggleBeat() }
        Btn(if (Store.set.motor) "■  Stop plato" else "▶  Start plato", kind = "ghost", small = true) { Inputs.toggleMotor() }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val key = Store.curSound()?.key
            val cues = if (key != null) Store.cuesOf(key) else List(4) { Double.NaN }
            for (i in 0 until 4) CuePad(i, !cues[i].isNaN(), compact)
        }
        if (!compact) Text("tocá: salta · mantené: guarda", style = T.tiny.copy(fontSize = 10.sp))
        Btn("Inicio y fin", kind = "ghost", small = true, enabled = Store.audioReady) { Nav.libreEdit = !Nav.libreEdit }
    }
}

@Composable
private fun CuePad(i: Int, set: Boolean, compact: Boolean) {
    val col = listOf(C.cue, C.deck2, C.ok, C.danger)[i]
    Box(
        Modifier.size(if (compact) 32.dp else 36.dp).clip(RoundedCornerShape(9.dp)).background(if (set) col.copy(alpha = 0.22f) else C.raised)
            .border(1.dp, if (set) col else C.border, RoundedCornerShape(9.dp))
            .pointerInput(i, set) {
                detectTapGestures(
                    onTap = { Inputs.hotCue(i, !set) },          // vacío: lo guarda; con cue: salta
                    onLongPress = { Inputs.hotCue(i, true) },     // mantener: lo guarda acá
                )
            },
        contentAlignment = Alignment.Center,
    ) { Text("${i + 1}", style = T.num.copy(fontSize = 14.sp, color = if (set) col else C.dim)) }
}

/** Ajuste en vivo: inicio y largo de la pista, cue y fin del sonido. */
@Composable
fun LibreEditPanel(frame: Long) {
    var tab by remember { mutableStateOf("pista") }
    var rev by remember { mutableIntStateOf(0) }
    val beat = Store.curBeat()
    val snd = Store.curSound()
    fun close() {
        Nav.libreEdit = false
        snd?.let { Store.saveAudioMeta(it.key) }
        if (Session.gridMoved && beat != null) {
            Nav.toast = "Moviste el 1 de ${beat.name}: los ejercicios con esta pista usan la grilla nueva"
            Session.gridMoved = false
        }
    }
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(10.dp).clip(R14).background(C.panel).border(1.dp, C.border, R14)
            .clickable(remember { MutableInteractionSource() }, null) {}.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Seg(listOf("pista" to "Pista", "sonido" to "Sonido"), tab) { tab = it }
            Spacer(Modifier.weight(1f))
            CloseX(::close)
        }
        @Suppress("UNUSED_VARIABLE") val r = rev
        if (tab == "pista" && beat != null) {
            val spb = 60.0 / beat.bpm
            var drag by remember { mutableDoubleStateOf(Double.NaN) }
            WaveBox(beat.peaks, 0.0, beat.duration, 58, { v, ph ->
                if (ph == UP) {
                    if (!drag.isNaN()) { val s = drag; Session.editBeat(true) { it.start = s.coerceIn(0.0, max(0.0, it.duration - 0.5)) }; rev++ }
                    drag = Double.NaN
                } else {
                    drag = v
                }
            }) { X ->
                @Suppress("UNUSED_VARIABLE") val fr = frame
                val st = if (drag.isNaN()) beat.start else drag
                val end = if (beat.loop) st + beat.loopLen() else beat.duration
                drawRect(C.accent.copy(alpha = 0.16f), Offset(X(st), 0f), Size(X(end) - X(st), size.height))
                drawRect(C.bg.copy(alpha = 0.55f), Offset.Zero, Size(X(st), size.height))
                marker(X(st), C.cue)
                if (beat.loop) marker(X(end), C.accentText)
                // lo que suena ahora
                if (Session.running && !Session.beatPaused) Session.musical(Session.heardBeat)?.let { m -> beat.secAt(m)?.let { drawRect(Color.White, Offset(X(it) - density, 0f), Size(2 * density, size.height)) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text("1 en ${"%.3f".format(beat.start)} s", style = T.monoS, modifier = Modifier.widthIn(min = 96.dp))
                listOf("−1 t" to -spb, "−10 ms" to -0.01, "+10 ms" to 0.01, "+1 t" to spb).forEach { (l, d) ->
                    Btn(l, kind = "ghost", small = true) { Session.editBeat(true) { it.start = (it.start + d).coerceIn(0.0, max(0.0, it.duration - 0.5)) }; rev++ }
                }
                Btn("El 1 acá", small = true, enabled = Session.running && !Session.beatPaused) { Session.markOne(); rev++ }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Seg(listOf(true to "Loop", false to "Libre"), beat.loop) { v -> Session.editBeat(false) { it.loop = v }; rev++ }
                if (beat.loop) Seg((1..8).map { it to "${it}c" }, beat.bars) { v -> Session.editBeat(false) { it.bars = v }; rev++ }
            }
            Text("Tocá «El 1 acá» justo en el golpe que querés como 1, o arrastrá sobre la onda. Se aplica directo, sin cortar la pista.", style = T.tiny)
        }
        if (tab == "sonido" && snd != null) {
            var active by remember { mutableStateOf("cue") }
            fun setCue(v: Double) {
                val pos = Inputs.posSec()
                snd.cue = v.coerceIn(0.0, max(0.0, snd.end - 0.05)); Engine.nSetCue(snd.cue)
                Inputs.p = max(0.0, (pos - snd.cue) / UNIT); Inputs.send()   // el disco queda donde estaba
                rev++
            }
            fun setEnd(v: Double) { snd.end = v.coerceIn(kotlin.math.min(snd.duration, snd.cue + 0.05), snd.duration); Engine.nSetEnd(snd.end); rev++ }
            fun set(v: Double) = if (active == "cue") setCue(v) else setEnd(v)
            WaveBox(snd.peaks, 0.0, snd.duration, 58, { v, ph ->
                when (ph) {
                    DOWN -> { active = if (abs(v - snd.cue) <= abs(v - snd.end)) "cue" else "fin"; set(v) }
                    MOVE -> set(v)
                    else -> Store.saveAudioMeta(snd.key)
                }
            }) { X ->
                @Suppress("UNUSED_VARIABLE") val fr = frame
                val xc = X(snd.cue); val xe = X(snd.end)
                drawRect(C.bg.copy(alpha = 0.62f), Offset.Zero, Size(xc, size.height))
                drawRect(C.bg.copy(alpha = 0.62f), Offset(xe, 0f), Size(size.width - xe, size.height))
                marker(xc, C.cue); marker(xe, C.deck2)
                Store.cuesOf(snd.key).forEachIndexed { i, c -> if (!c.isNaN()) drawRect(listOf(C.cue, C.deck2, C.ok, C.danger)[i], Offset(X(c) - density, size.height * 0.7f), Size(2 * density, size.height * 0.3f)) }
                drawRect(Color.White, Offset(X(Inputs.posSec()) - density, 0f), Size(2 * density, size.height))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Seg(listOf("cue" to "Cue", "fin" to "Fin"), active) { active = it }
                Text(if (active == "cue") "${"%.3f".format(snd.cue)} s" else "${"%.3f".format(snd.end)} s", style = T.monoS, modifier = Modifier.widthIn(min = 64.dp))
                listOf("−10 ms" to -0.01, "+10 ms" to 0.01).forEach { (l, d) -> Btn(l, kind = "ghost", small = true) { set((if (active == "cue") snd.cue else snd.end) + d); Store.saveAudioMeta(snd.key) } }
                Btn(if (active == "cue") "Cue acá" else "Fin acá", small = true) { set(Inputs.posSec()); Store.saveAudioMeta(snd.key) }
            }
            Text("El cue es donde frena el disco para atrás; después del fin, silencio. Los cues 1-4 (pads de la REV1) se ven abajo de la onda.", style = T.tiny)
        }
    }
}

/** Lectura al parar Libre: un corte abre la pantalla en dos. */
@Composable
fun LibreReading(r: LibreRead) {
    val prog = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { prog.animateTo(1f, tween(640, easing = LinearEasing)) }
    val rec = r.rec
    Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.96f)).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scratch libre · ${rec.bpm} BPM · ${rec.dur / 60}:${"%02d".format(rec.dur % 60)}", style = T.small)
                        Text("Lectura", style = T.h1)
                    }
                    CloseX { Session.closeLibreRead() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Trucos", "${r.tricks}", if (r.counts.isNotEmpty()) "${r.counts.size} distintos" else null, Modifier.weight(1f))
                    Stat("Clicks/s", "%.1f".format(r.cpsAvg), "pico ${r.cpsPeak}", Modifier.weight(1f))
                    Stat("Mejor combo", if (r.bestCombo >= 2) "×${r.bestCombo}" else "—", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Cortes limpios", r.cleanPct?.let { "$it%" } ?: "—", "${r.cuts} cortes", Modifier.weight(1f))
                    Stat("En grilla", r.gridPct?.let { "$it%" } ?: "—", r.gridType?.let { "sobre todo en $it" } ?: "con el beat sonando", Modifier.weight(1f))
                }
                H2("Trucos que hiciste")
                if (r.counts.isEmpty()) Text("Esta vez no reconocí trucos. Probá baby, chirps, flares, transformers o crabs sobre el beat.", style = T.body.copy(color = C.text2))
                val top = r.counts.maxOfOrNull { it.second } ?: 1
                r.counts.forEach { (name, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = T.bodyB.copy(fontSize = 14.sp), modifier = Modifier.width(150.dp), maxLines = 1)
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(8.dp)).background(C.dark)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(n.toFloat() / top).background(C.accent))
                        }
                        Text("×$n", style = T.monoS, modifier = Modifier.width(52.dp).padding(start = 10.dp))
                    }
                }
                Text("Se guardó en Progreso como sesión libre.", style = T.tiny, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Btn("Cerrar", Modifier.weight(1f), "ghost") { Session.closeLibreRead() }
                    Btn("Otra vez", Modifier.weight(1f)) { Session.closeLibreRead(); Session.start(false) }
                }
            }
        }
        // el corte: una línea cruza la pantalla y las dos mitades se abren
        Canvas(Modifier.fillMaxSize()) {
            val t = prog.value
            if (t >= 1f) return@Canvas
            val W = size.width; val H = size.height
            val k = H * 0.07f
            val head = (t / 0.22f).coerceAtMost(1f)
            val o = ((t - 0.22f) / 0.78f).coerceIn(0f, 1f)
            val e = 1 - (1 - o) * (1 - o) * (1 - o)
            val dy = e * (H / 2 + k)
            val topHalf = Path().apply { moveTo(0f, -dy); lineTo(W, -dy); lineTo(W, H / 2 - k - dy); lineTo(0f, H / 2 + k - dy); close() }
            val botHalf = Path().apply { moveTo(0f, H / 2 + k + dy); lineTo(W, H / 2 - k + dy); lineTo(W, H + dy); lineTo(0f, H + dy); close() }
            drawPath(topHalf, C.bg); drawPath(botHalf, C.bg)
            val a = Offset(0f, H / 2 + k); val b = Offset(W * head, H / 2 + k - 2 * k * head)
            drawLine(Color.White, a, b, 3 * density, alpha = 1 - o)
            drawLine(C.accent, a, b, 9 * density, alpha = 0.35f * (1 - o))
        }
    }
}
