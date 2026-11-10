package com.agenciapeluda.kutty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenciapeluda.kutty.audio.Engine
import com.agenciapeluda.kutty.audio.Inputs
import com.agenciapeluda.kutty.data.Store
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

object Perms { var requestMic: (() -> Unit)? = null }

@Composable
private fun SRow(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                if (hint != null) Text(hint, style = T.small.copy(color = C.dim))
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
    }
}

@Composable
private fun KSlider(value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = { Store.save() },
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.divider))
        Text(label, style = T.monoS, modifier = Modifier.width(64.dp).padding(start = 10.dp))
    }
}

private fun srcName(s: String) = when (s) { "dvs" -> "DVS (timecode)"; "midi" -> "Controladora MIDI"; else -> "Pantalla" }

@Composable
fun AjustesScreen() {
    val s = Store.set
    var dvs by remember { mutableStateOf(FloatArray(5)) }
    var lat by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            Inputs.tick(); dvs = Inputs.dvsState.copyOf()
            lat = (Engine.nLatency() / Engine.sampleRate * 1000).toFloat()
            delay(120)
        }
    }
    fun saveIt() { Store.save(); Inputs.resolve() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Ajustes", style = T.h1)

        H2("Fuentes")
        SRow("Disco", "En uso: ${srcName(Inputs.discActive)}") {
            Seg(listOf("auto" to "Auto", "dvs" to "DVS", "midi" to "MIDI", "touch" to "Pantalla"), s.discSrc) { s.discSrc = it; saveIt() }
        }
        SRow("Fader", "En uso: " + when (Inputs.faderActive) { "dvs" -> "corte del timecode"; "midi" -> "crossfader MIDI"; else -> "pantalla" }) {
            Seg(listOf("auto" to "Auto", "dvs" to "Corte", "midi" to "MIDI", "touch" to "Pantalla"), s.faderSrc) { s.faderSrc = it; saveIt() }
        }

        H2("DVS (vinilo de timecode)")
        SRow("Placa de audio", if (Inputs.audioInputs.isEmpty()) "Conectá una placa USB con entrada estéreo" else "Entrada donde llega el plato") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(0 to "Ninguna") + Inputs.audioInputs.map { it.id to it.name }).forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().clip(R12).background(C.dark).border(1.dp, if (s.dvsDevice == id) C.accent else C.divider, R12).clickable {
                        s.dvsDevice = id; Store.save()
                        if (id != 0 && !Inputs.hasMicPermission) Perms.requestMic?.invoke() else Inputs.reopenDvs()
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(if (s.dvsDevice == id) C.accentText else C.tick))
                        Spacer(Modifier.width(10.dp)); Text(name, style = T.body)
                    }
                }
            }
        }
        if (s.dvsDevice != 0) {
            SRow("Señal del timecode", if (Inputs.dvsOpen) "Poné el disco a girar" else "No se pudo abrir la entrada") {
                val sig = dvs[2] > 0.5f
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (sig) C.ok else C.danger))
                    Text(if (sig) "Leyendo" else "Sin señal", style = T.bodyB)
                    Text("vel ${"%+.2f".format(dvs[0])}", style = T.monoS)
                    Text("nivel ${(dvs[1] * 100).roundToInt()}", style = T.monoS)
                    Text("calidad ${(dvs[3] * 100).roundToInt()}%", style = T.monoS)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(C.dark)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((dvs[1] / 0.5f).coerceIn(0f, 1f)).background(if (sig) C.ok else C.cue))
                }
            }
            SRow("Umbral de señal", "Por debajo, la app considera el fader cerrado (o el disco suelto)") {
                KSlider(s.dvsThreshold, 0.005f..0.2f, "%.3f".format(s.dvsThreshold)) { s.dvsThreshold = it; Engine.nDvsConfig(it, 1000f, s.dvsInvert) }
            }
            SRow("Dirección", "Si el disco va al revés en el gráfico, invertila") {
                Seg(listOf(false to "Normal", true to "Invertida"), s.dvsInvert) { s.dvsInvert = it; Engine.nDvsConfig(s.dvsThreshold, 1000f, it); Store.save() }
            }
        }

        H2("Controladora MIDI")
        SRow("Conectadas", if (Inputs.midiDevices.isEmpty()) "Ninguna. La REV1 y la Mixtour se reconocen solas" else Inputs.midiDevices.joinToString(" · ")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (Inputs.learning == "jog") "Girá el jog…" else if (s.learnJog.isNotEmpty()) "Jog aprendido" else "Aprender jog", kind = "ghost", small = true) { Inputs.learning = "jog" }
                Btn(if (Inputs.learning == "fader") "Mové el fader…" else if (s.learnFader.isNotEmpty()) "Fader aprendido" else "Aprender fader", kind = "ghost", small = true) { Inputs.learning = "fader" }
            }
        }
        SRow("Lado del crossfader", "Hacia dónde está el deck del scratch") {
            Seg(listOf("left" to "Izquierda", "right" to "Derecha"), s.xfaderSide) { s.xfaderSide = it; Store.save() }
        }
        SRow("Cut lag del crossfader", "Recorrido desde el borde antes de que suene") {
            KSlider(s.xfaderCutLag, 0f..0.2f, "${(s.xfaderCutLag * 100).roundToInt()}%") { s.xfaderCutLag = it }
        }
        SRow("Pasos del jog por vuelta") { KSlider(s.jogSteps.toFloat(), 200f..4000f, "${s.jogSteps}") { s.jogSteps = it.roundToInt() } }

        H2("Audio")
        SRow("Pista", "Beat o tema, en loop o libre, con su BPM y su 1") { Btn(beatLabel(), kind = "ghost", small = true, enabled = Store.audioReady) { Nav.chooser = "beat" } }
        SRow("Sonido a scratchear", "Subí tu sonido y marcá el cue sobre la onda") { Btn(Store.curSound()?.name ?: "—", kind = "ghost", small = true, enabled = Store.audioReady) { Nav.chooser = "sound" } }
        SRow("Ondas de fondo", "La onda de la pista detrás del gráfico y la del sonido arriba") { Seg(listOf(true to "Mostrar", false to "Ocultar"), s.waves) { s.waves = it; Store.save() } }
        SRow("Volumen del beat") { KSlider(s.beatVol, 0f..1f, "${(s.beatVol * 100).roundToInt()}%") { s.beatVol = it; Engine.nSetVolumes(s.beatVol, s.scratchVol) } }
        SRow("Volumen del scratch") { KSlider(s.scratchVol, 0f..1f, "${(s.scratchVol * 100).roundToInt()}%") { s.scratchVol = it; Engine.nSetVolumes(s.beatVol, s.scratchVol) } }
        SRow("Metrónomo") { Seg(listOf(false to "Solo la cuenta", true to "Siempre"), s.metro) { s.metro = it; Store.save() } }

        H2("Disco en pantalla")
        SRow("Gesto", "Lineal: deslizá hacia arriba para empujar") { Seg(listOf("circular" to "Circular", "lineal" to "Arriba / abajo"), s.platter) { s.platter = it; Store.save() } }
        SRow("Motor", "Prendido: al soltar, el disco sigue girando como un plato real") { Seg(listOf(false to "Apagado", true to "Prendido"), s.motor) { s.motor = it; Store.save() } }
        SRow("Sensibilidad") { KSlider(s.sens, 0.4f..2.5f, "×${"%.2f".format(s.sens)}") { s.sens = it } }

        H2("Fader en pantalla")
        SRow("Modo", "Botón: mientras lo tocás, suena") { Seg(listOf("slide" to "Deslizar", "pad" to "Botón"), s.faderMode) { s.faderMode = it; Store.save() } }
        SRow("Cut-in", "Cuánto hay que mover el fader para que suene") { KSlider(s.cutIn, 0.02f..0.4f, "${(s.cutIn * 100).roundToInt()}%") { s.cutIn = it } }
        SRow("Hamster") { Seg(listOf(false to "Normal", true to "Invertido"), s.hamster) { s.hamster = it; Store.save() } }

        H2("Precisión")
        SRow("Exigencia") { Seg(listOf("facil" to "Fácil ±90", "normal" to "Normal ±60", "pro" to "Pro ±35"), s.diff) { s.diff = it; Store.save() } }
        SRow("Largo de la práctica") { Seg(listOf(4 to "4 compases", 8 to "8 compases"), s.bars) { s.bars = it; Store.save() } }
        SRow("Latencia", "Salida de audio: ${lat.roundToInt()} ms. Si la app te marca siempre tarde o antes, corregilo acá") {
            KSlider(s.calib.toFloat(), -60f..60f, "${if (s.calib > 0) "+" else ""}${s.calib} ms") { s.calib = it.roundToInt() }
        }

        H2("Datos")
        var confirm by remember { mutableStateOf(false) }
        SRow("Progreso guardado en este teléfono") {
            if (!confirm) Btn("Borrar progreso", kind = "danger", small = true) { confirm = true }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Cancelar", kind = "ghost", small = true) { confirm = false }
                Btn("Sí, borrar todo", kind = "danger", small = true) { Store.resetProgress(); confirm = false; Nav.toast = "Progreso borrado" }
            }
        }
        Text("Kutty · hecho por Lui Agüero", style = T.tiny.copy(fontSize = 12.sp), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp, bottom = 12.dp))
    }
}
