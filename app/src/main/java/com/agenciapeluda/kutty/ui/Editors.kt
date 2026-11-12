package com.agenciapeluda.kutty.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agenciapeluda.kutty.audio.AudioLib
import com.agenciapeluda.kutty.audio.BeatItem
import com.agenciapeluda.kutty.audio.Engine
import com.agenciapeluda.kutty.audio.Inputs
import com.agenciapeluda.kutty.audio.Peaks
import com.agenciapeluda.kutty.audio.UNIT
import com.agenciapeluda.kutty.core.Session
import com.agenciapeluda.kutty.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pone en el motor el sonido elegido (cue y fin incluidos). */
fun applySound() {
    val s = Store.curSound() ?: return
    Engine.nSetSample(s.data)
    Engine.nSetCue(s.cue)
    Engine.nSetEnd(s.end)
    Inputs.resetDisc()
}

/** Pone en el motor el sonido y la pista elegidos. */
fun applyAudio() {
    Store.curBeat()?.let { Engine.nSetBeat(it.data) }
    applySound()
}

fun selectAudio(kind: String, key: String) {
    if (kind == "sound") {
        val same = Store.set.sound == key
        Store.set.sound = key; Store.save()
        if (!same) applySound()
        return
    }
    Store.set.beat = key
    val b = Store.beats[key]
    if (b != null && !Session.running) Session.bpm = if (b.builtin) (Session.tech?.bpm ?: 85) else b.bpm.roundToInt()
    Store.save(); applyAudio()
}

/** Suena el sonido de cue a fin. */
fun previewSound(key: String) {
    val s = Store.sounds[key] ?: return
    Engine.nPreview(s.cue, max(0.05, s.end - s.cue))
}

@Composable
fun ChooserDialog(kind: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val isS = kind == "sound"
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val key = runCatching { withContext(Dispatchers.Default) { Store.importAudio(ctx, uri, kind) } }.getOrNull()
            busy = false
            if (key == null) { Nav.toast = "No pude leer ese archivo"; return@launch }
            selectAudio(kind, key)
            Nav.chooser = null
            if (isS) Nav.soundEditor = key else Nav.trackEditor = key
        }
    }
    Modal(if (isS) "Sonido a scratchear" else "Pista", { if (isS) Engine.nStopPreview(); Nav.chooser = null }) {
        Text(
            if (isS) "Subí una frase, una voz o un sample: la app arma la onda y pone el cue en el primer ataque. Lo podés mover."
            else "Subí un beat o un tema. Elegís si suena en loop (1 a 8 compases) o libre; la app detecta el BPM y el 1.",
            style = T.small, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        val cur = if (isS) Store.set.sound else Store.set.beat
        val items: List<Triple<String, String, String>> = if (isS) Store.sounds.values.sortedBy { !it.builtin }.map { Triple(it.key, it.name, "%.2f s · cue %.2f".format(it.end - it.cue, it.cue)) }
        else Store.beats.values.sortedBy { !it.builtin }.map { Triple(it.key, it.name, (if (it.loop) "loop ${it.bars}c" else "libre") + " · " + (Math.round(it.bpm * 10) / 10.0)) }
        items.forEach { (k, name, meta) ->
            val on = k == cur
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(R12).background(C.dark).border(1.dp, if (on) C.accent else C.divider, R12), verticalAlignment = Alignment.CenterVertically) {
                // sonido: tocar la fila lo elige y lo hace sonar, el modal queda abierto
                Row(Modifier.weight(1f).clickable { selectAudio(kind, k); if (isS) previewSound(k) else Nav.chooser = null }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) C.accentText else C.tick))
                    Spacer(Modifier.width(10.dp))
                    Text(name, style = T.bodyB, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(meta, style = T.monoS.copy(color = C.dim))
                }
                val builtin = if (isS) false else Store.beats[k]?.builtin == true
                if (!builtin) Btn("Editar", Modifier.padding(end = 6.dp), "ghost", small = true) {
                    selectAudio(kind, k); Nav.chooser = null
                    if (isS) Nav.soundEditor = k else Nav.trackEditor = k
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (busy) "Leyendo…" else if (isS) "Subir sonido" else "Subir pista", Modifier.weight(1f), if (isS) "ghost" else "primary", enabled = !busy) { pick.launch("audio/*") }
            if (isS) Btn("Listo", Modifier.weight(1f)) { Engine.nStopPreview(); Nav.chooser = null }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

const val DOWN = 0
const val MOVE = 1
const val UP = 2

/** Onda con un arrastre que devuelve el segundo tocado y la fase (DOWN, MOVE, UP). */
@Composable
internal fun WaveBox(pk: Peaks, s0: Double, s1: Double, height: Int, onSec: (Double, Int) -> Unit, over: androidx.compose.ui.graphics.drawscope.DrawScope.((Double) -> Float) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(height.dp).clip(R12).background(C.dark).border(1.dp, C.divider, R12).pointerInput(s0, s1) {
        awaitEachGesture {
            val d = awaitFirstDown()
            onSec(s0 + d.position.x / size.width * (s1 - s0), DOWN)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                if (!ch.pressed) break
                onSec(s0 + ch.position.x / size.width * (s1 - s0), MOVE); ch.consume()
            }
            onSec(Double.NaN, UP)
        }
    }) {
        waveRange(pk, s0, s1, 0f, size.height)
        over { s -> ((s - s0) / (s1 - s0) * size.width).toFloat() }
    }
}

@Composable
fun SoundEditor(key: String) {
    val s = Store.sounds[key] ?: run { Nav.soundEditor = null; return }
    var cue by remember { mutableStateOf(s.cue) }
    var end by remember { mutableStateOf(s.end) }
    var active by remember { mutableStateOf("cue") }     // marcador que se mueve: cue | fin
    var zoomCenter by remember { mutableStateOf(s.cue) }
    val Z = 0.35
    val isCur = Store.set.sound == key
    fun setCue(v: Double) { cue = v.coerceIn(0.0, max(0.0, end - 0.05)); s.cue = cue; if (isCur) Engine.nSetCue(cue) }
    fun setEnd(v: Double) { end = v.coerceIn(min(s.duration, cue + 0.05), s.duration); s.end = end; if (isCur) Engine.nSetEnd(end) }
    fun setActive(v: Double) { if (active == "cue") setCue(v) else setEnd(v) }
    fun focus() { zoomCenter = if (active == "cue") cue else end }
    fun done() { Engine.nStopPreview(); s.cue = cue; s.end = end; Store.saveAudioMeta(key); if (isCur) applySound(); Nav.soundEditor = null }
    fun androidx.compose.ui.graphics.drawscope.DrawScope.trim(X: (Double) -> Float) {
        // lo de afuera apagado, la región resaltada
        val xc = X(cue).coerceIn(0f, size.width); val xe = X(end).coerceIn(0f, size.width)
        if (xc > 0f) drawRect(C.bg.copy(alpha = 0.62f), Offset.Zero, Size(xc, size.height))
        if (xe < size.width) drawRect(C.bg.copy(alpha = 0.62f), Offset(xe, 0f), Size(size.width - xe, size.height))
        if (xe > xc) drawRect(C.accent.copy(alpha = 0.10f), Offset(xc, 0f), Size(xe - xc, size.height))
        marker(X(cue), C.cue)
        marker(X(end), C.deck2)
    }
    Modal(s.name, ::done) {
        Text("Tocá o arrastrá sobre la onda: se mueve el marcador más cercano. Ámbar es el cue (para atrás frena ahí), celeste es el fin (después, silencio).", style = T.small, modifier = Modifier.padding(bottom = 8.dp))
        WaveBox(s.peaks, 0.0, s.duration, 84, { v, ph ->
            when (ph) {
                DOWN -> { active = if (kotlin.math.abs(v - cue) <= kotlin.math.abs(v - end)) "cue" else "fin"; setActive(v); focus() }
                MOVE -> { setActive(v); focus() }
                else -> focus()
            }
        }) { X -> trim(X) }
        Spacer(Modifier.height(10.dp))
        WaveBox(s.peaks, zoomCenter - Z, zoomCenter + Z, 110, { v, ph -> if (ph == UP) focus() else setActive(v) }) { X ->
            if (active == "cue") drawRect(C.accent.copy(alpha = 0.14f), Offset(X(cue), 0f), Size(X(min(end, cue + UNIT)) - X(cue), size.height))
            trim(X)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Seg(listOf("cue" to "Mover cue", "fin" to "Mover fin"), active) { active = it; focus() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cue ${"%.3f".format(cue)} · fin ${"%.3f".format(end)} · ${"%.2f".format(end - cue)} s", style = T.monoS, modifier = Modifier.weight(1f))
            Btn("Auto", kind = "ghost", small = true) {
                val c = AudioLib.autoCue(s.data, s.sr)
                end = s.duration; setCue(c); setEnd(AudioLib.autoEnd(s.data, s.sr, cue)); focus()
            }
            Btn("Escuchar", kind = "ghost", small = true) { Engine.nPreview(cue, max(0.05, end - cue)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!s.builtin) Btn("Borrar", Modifier.weight(1f), "danger") { Store.deleteAudio(key); applySound(); Nav.soundEditor = null }
            Btn("Listo", Modifier.weight(1f)) { done() }
        }
    }
}

@Composable
fun TrackEditor(key: String) {
    val b: BeatItem = Store.beats[key] ?: run { Nav.trackEditor = null; return }
    var start by remember { mutableStateOf(b.start) }
    var bpm by remember { mutableStateOf(b.bpm) }
    var loop by remember { mutableStateOf(b.loop) }
    var bars by remember { mutableIntStateOf(b.bars) }
    var bpmText by remember { mutableStateOf(fmt(b.bpm)) }
    var playing by remember { mutableStateOf(false) }
    var zoomCenter by remember { mutableStateOf(b.start) }
    val Z = 1.2
    fun sync() { b.start = start; b.bpm = bpm; b.loop = loop; b.bars = bars }
    fun stopPlay() { if (playing) { Engine.nStopBeat(); playing = false } }
    fun done() { stopPlay(); sync(); Store.saveAudioMeta(key); Nav.trackEditor = null; selectAudio("beat", key) }
    val spb = 60.0 / bpm
    val loopEnd = min(b.duration, start + bars * 4 * spb)
    fun androidx.compose.ui.graphics.drawscope.DrawScope.grid(X: (Double) -> Float, s0: Double, s1: Double, fine: Boolean) {
        var k = kotlin.math.ceil((s0 - start) / spb).toInt()
        while (true) {
            val t = start + k * spb; if (t > s1) break
            if (t >= 0) { val bar = k % 4 == 0; if (fine || bar) drawRect(Color.White.copy(alpha = if (bar) 0.35f else 0.14f), Offset(X(t), 0f), Size(1f, size.height)) }
            k++
        }
    }
    Modal(b.name, ::done) {
        Text("Tocá la onda para marcar el 1 (ámbar). Con Probar suena con un clic: tiene que caer con los golpes; si no, ajustá el BPM.", style = T.small, modifier = Modifier.padding(bottom = 8.dp))
        WaveBox(b.peaks, 0.0, b.duration, 84, { v, ph -> if (ph == UP) zoomCenter = start else { start = v.coerceIn(0.0, max(0.0, b.duration - 0.5)); zoomCenter = start; stopPlay() } }) { X ->
            if (loop) drawRect(C.accent.copy(alpha = 0.18f), Offset(X(start), 0f), Size(X(loopEnd) - X(start), size.height))
            grid(X, 0.0, b.duration, false)
            drawRect(C.bg.copy(alpha = 0.55f), Offset.Zero, Size(X(start), size.height))
            marker(X(start), C.cue)
            if (loop) marker(X(loopEnd), C.accentText)
        }
        Spacer(Modifier.height(10.dp))
        WaveBox(b.peaks, zoomCenter - Z, zoomCenter + Z, 110, { v, ph -> if (ph == UP) zoomCenter = start else { start = v.coerceIn(0.0, max(0.0, b.duration - 0.5)); stopPlay() } }) { X ->
            grid(X, zoomCenter - Z, zoomCenter + Z, true)
            marker(X(start), C.cue)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Inicio: ${"%.3f".format(start)} s", style = T.monoS, modifier = Modifier.weight(1f))
            Btn("Auto", kind = "ghost", small = true) { start = AudioLib.autoDownbeat(b.peaks); zoomCenter = start }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BPM", style = T.small, modifier = Modifier.weight(1f))
            BasicTextField(bpmText, { v -> bpmText = v; v.replace(',', '.').toDoubleOrNull()?.let { if (it in 50.0..200.0) bpm = it } }, singleLine = true,
                textStyle = T.num.copy(fontSize = 15.sp()), cursorBrush = SolidColor(C.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(84.dp).clip(R12).background(C.dark).border(1.dp, C.border, R12).padding(10.dp))
            Btn("Detectar", kind = "ghost", small = true) { bpm = AudioLib.detectBpm(b.peaks); bpmText = fmt(bpm) }
            Btn("×2", kind = "ghost", small = true) { if (bpm * 2 <= 200) { bpm *= 2; bpmText = fmt(bpm) } }
            Btn("÷2", kind = "ghost", small = true) { if (bpm / 2 >= 50) { bpm /= 2; bpmText = fmt(bpm) } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Seg(listOf(true to "Loop", false to "Libre"), loop) { loop = it; stopPlay() }
            if (loop) Seg(listOf(1 to "1c", 2 to "2c", 4 to "4c", 8 to "8c"), bars) { bars = it; stopPlay() }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (playing) "Parar" else "Probar", Modifier.weight(1f), "ghost") {
                if (playing) { stopPlay(); return@Btn }
                sync(); Engine.nSetBeat(b.data)
                val f0 = Engine.nWritten() + 0.1 * Engine.sampleRate
                Engine.nStartBeat(f0.toLong(), start, 1.0, loop, b.loopLen())
                for (k in 0 until 16) Engine.nClick((f0 + k * spb * Engine.sampleRate).toLong(), k % 4 == 0)
                playing = true
            }
            Btn("Borrar", Modifier.weight(1f), "danger") { stopPlay(); Store.deleteAudio(key); applyAudio(); Nav.trackEditor = null }
            Btn("Listo", Modifier.weight(1f)) { done() }
        }
    }
}

private fun fmt(v: Double) = (Math.round(v * 10) / 10.0).toString()
