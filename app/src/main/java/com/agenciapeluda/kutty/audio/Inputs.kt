package com.agenciapeluda.kutty.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agenciapeluda.kutty.data.Store

const val UNIT = 0.45   // segundos de sonido por unidad de posición (1 = un cuarto de vuelta)
const val REV = 1.8     // segundos por vuelta a 33⅓

class AudioInput(val id: Int, val name: String)

/**
 * Disco y fader: junta la pantalla, las controladoras MIDI y el DVS, decide qué
 * fuente usar y se lo pasa al motor.
 */
object Inputs {
    // estado del disco (unidades: 1 = un cuarto de vuelta desde el cue)
    @Volatile var p = 0.0
    @Volatile var vel = 0.0
    @Volatile var touching = false
    private var lastT = 0L
    @Volatile var demo = false

    // fader
    @Volatile var fv = 0f            // posición del fader en pantalla 0..1
    @Volatile var padOpen = false
    @Volatile var midiFader = -1f    // crossfader MIDI 0..1 (-1: nunca llegó)
    // práctica por partes: la app hace el fader (Solo plato) o el disco (Solo fader)
    @Volatile var autoFader = false
    @Volatile var autoDisc = false
    @Volatile var autoFaderOpen = false

    // fuentes en uso (para mostrar en Ajustes)
    var discActive by mutableStateOf("touch")
    var faderActive by mutableStateOf("touch")

    // MIDI
    val midiDevices = mutableStateListOf<String>()
    var hasJog by mutableStateOf(false)
    var hasXfader by mutableStateOf(false)
    var learning by mutableStateOf("")   // "jog" | "fader" | ""
    private var lsb = 0
    private val opened = ArrayList<MidiDevice>()
    private val ports = ArrayList<MidiOutputPort>()
    @Volatile private var lastJog = 0L

    // DVS
    val audioInputs = mutableStateListOf<AudioInput>()
    val audioOutputs = mutableStateListOf<AudioInput>()
    var outActive by mutableStateOf("Teléfono")
    private var outId = Int.MIN_VALUE
    var dvsOpen by mutableStateOf(false)
    val dvsState = FloatArray(5)  // velocidad, nivel, señal, calidad, abierta
    @Volatile private var lastSignal = 0L

    fun cueSec() = Store.curSound()?.cue ?: 0.03

    fun faderOpen(): Boolean {
        val s = Store.set
        if (autoFader) return autoFaderOpen
        return when (faderActive) {
            "midi" -> {
                val x = midiFader.coerceIn(0f, 1f)
                val lag = s.xfaderCutLag
                val v = if (s.xfaderSide == "left") 1f - x else x
                val o = v > lag
                if (s.hamster) !o else o
            }
            "dvs" -> dvsState[2] > 0.5f
            else -> if (s.faderMode == "pad") padOpen else if (s.faderMode == "cut") !padOpen else {
                val v = if (s.hamster) 1f - fv else fv
                v >= s.cutIn
            }
        }
    }

    /** Elige las fuentes según lo que hay conectado (o lo que se fijó en Ajustes). */
    fun resolve() {
        val s = Store.set
        val now = SystemClock.uptimeMillis()
        val dvsAlive = dvsOpen && now - lastSignal < 3000
        val disc = when (s.discSrc) {
            "dvs" -> "dvs"; "midi" -> "midi"; "touch" -> "touch"
            else -> if (dvsAlive) "dvs" else if (hasJog) "midi" else "touch"
        }
        val fader = when (s.faderSrc) {
            "dvs" -> "dvs"; "midi" -> "midi"; "touch" -> "touch"
            else -> if (hasXfader) "midi" else if (disc == "dvs") "dvs" else "touch"
        }
        if (disc != discActive) discActive = disc
        if (fader != faderActive) faderActive = fader
        if (demo) Engine.nSetSources(0, 0)
        else Engine.nSetSources(if (disc == "dvs" && !autoDisc) 1 else 0, if (fader == "dvs" && !autoFader) 1 else 0)
    }

    /** Llamar en cada cuadro. */
    fun tick() {
        if (dvsOpen) {
            Engine.nDvsState(dvsState)
            if (dvsState[2] > 0.5f) lastSignal = SystemClock.uptimeMillis()
        }
        val now = SystemClock.uptimeMillis()
        if (!demo) {
            if (autoDisc) { /* el disco lo mueve la app */ }
            else if (Store.set.motor && !touching && discActive != "dvs") {
                val dt = (now - lastT).coerceIn(0, 50) / 1000.0
                p += dt / UNIT; vel = 1 / UNIT; lastT = now; send()
            } else if (touching && now - lastT > 18 && vel != 0.0) { vel = 0.0; send() }
            if (lastJog != 0L && now - lastJog > 80) { lastJog = 0; touching = false; vel = 0.0; send() }
            if (!touching && !Store.set.motor) lastT = now
        }
        resolve()
        if (!demo && discActive != "dvs") Engine.setFader(faderOpen())
    }

    fun send() {
        if (demo) return
        if (!autoDisc) Engine.setTarget(cueSec() + p * UNIT, vel * UNIT)
        Engine.setFader(faderOpen())
    }

    /** Posición del fader para dibujar (0..1, 1 = abierto del lado del scratch), sea cual sea la fuente. */
    fun faderShown(): Float {
        val s = Store.set
        if (autoFader) return if (autoFaderOpen) 1f else 0f
        return when (faderActive) {
            "midi" -> { val x = midiFader.coerceIn(0f, 1f); if (s.xfaderSide == "left") 1f - x else x }
            "dvs" -> if (dvsState[2] > 0.5f) 1f else 0f
            else -> if (s.faderMode != "slide") (if (faderOpen()) 1f else 0f) else (if (s.hamster) 1f - fv else fv)
        }
    }

    /** dp en unidades; t en milisegundos (uptime). */
    fun moveDisc(dp: Double, t: Long) {
        if (autoDisc) return
        val dt = ((t - lastT).coerceAtLeast(1)) / 1000.0
        p += dp
        vel = vel * 0.5 + (dp / dt) * 0.5
        lastT = t
        send()
    }

    fun touchDisc(down: Boolean, t: Long) { touching = down; vel = 0.0; lastT = t; send() }
    fun setFaderPos(v: Float) { fv = v.coerceIn(0f, 1f); Engine.setFader(faderOpen()) }
    fun setPad(open: Boolean) { padOpen = open; Engine.setFader(faderOpen()) }

    fun resetDisc() {
        p = 0.0; vel = 0.0
        Engine.nJump(cueSec())
        if (dvsOpen) Engine.nDvsRecenter()
    }

    // ---------- MIDI ----------
    private var mm: MidiManager? = null
    fun initMidi(ctx: Context) {
        val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        mm = m
        @Suppress("DEPRECATION")
        m.devices.forEach { openMidi(it) }
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(d: MidiDeviceInfo) = openMidi(d)
            override fun onDeviceRemoved(d: MidiDeviceInfo) { refreshMidi() }
        }, Handler(Looper.getMainLooper()))
    }

    private fun devName(d: MidiDeviceInfo): String {
        val p = d.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME) ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI"
    }

    private fun openMidi(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0) return
        mm?.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            val port = dev.openOutputPort(0) ?: return@openDevice
            port.connect(Parser(devName(info)))
            opened.add(dev); ports.add(port)
            refreshMidi()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refreshMidi() {
        midiDevices.clear()
        @Suppress("DEPRECATION")
        val list = mm?.devices?.filter { it.outputPortCount > 0 }?.map { devName(it) } ?: emptyList()
        midiDevices.addAll(list)
        val s = Store.set
        val rev1 = list.any { it.contains("REV1", true) || it.contains("DDJ", true) }
        val mix = list.any { it.contains("Mixtour", true) }
        hasJog = rev1 || (s.learnJog.isNotEmpty() && list.isNotEmpty())
        hasXfader = rev1 || mix || (s.learnFader.isNotEmpty() && list.isNotEmpty())
    }

    private class Parser(val name: String) : MidiReceiver() {
        private var running = 0
        private val isRev1 = name.contains("REV1", true) || name.contains("DDJ", true)
        private val isMixtour = name.contains("Mixtour", true)
        private val buf = IntArray(3); private var n = 0
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xff
                if (b >= 0xf8) continue
                if (b >= 0x80) { running = b; n = 0; continue }
                buf[n++] = b
                val need = if ((running and 0xf0) == 0xc0 || (running and 0xf0) == 0xd0) 1 else 2
                if (n >= need) { handle(running, buf[0], if (need == 2) buf[1] else 0); n = 0 }
            }
        }
        private fun handle(st: Int, d1: Int, d2: Int) {
            val kind = st and 0xf0
            val sig = "$st:$d1"
            val s = Store.set
            if (learning.isNotEmpty() && (kind == 0xb0 || kind == 0xe0)) {
                Handler(Looper.getMainLooper()).post {
                    if (learning == "jog") s.learnJog = sig else if (learning == "fader") s.learnFader = sig
                    learning = ""; Store.save(); refreshMidi()
                }
                return
            }
            val t = SystemClock.uptimeMillis()
            monitor(name, st, d1, d2)
            val ch = st and 0x0f
            // DDJ de Pioneer (REV1): jog en CC 0x21/0x22 (0x23/0x29 con SHIFT) de los canales de deck,
            // toque en la nota 0x36; crossfader 14 bits en CC 0x1F/0x3F del canal 7
            if (isRev1) {
                if (kind == 0xb0 && ch <= 3 && (d1 == 0x21 || d1 == 0x22 || d1 == 0x23 || d1 == 0x29)) {
                    // la codificación no está confirmada: se toma la que da el paso más chico
                    val a = d2 - 64; val b = if (d2 >= 64) d2 - 128 else d2
                    jog(if (kotlin.math.abs(a) <= kotlin.math.abs(b)) a else b, t); return
                }
                if ((kind == 0x90 || kind == 0x80) && ch <= 3 && d1 == 0x36) {
                    val on = kind == 0x90 && d2 > 0
                    touching = on; lastJog = if (on) t else 0; if (!on) { vel = 0.0; send() }; return
                }
                if (kind == 0xb0 && d1 == 0x3f) { lsb = d2; return }
                if (kind == 0xb0 && d1 == 0x1f) { midiFader = ((d2 shl 7) or lsb) / 16383f; seenXfader(); Engine.setFader(faderOpen()); return }
            }
            if (isMixtour && st == 0xb0 && d1 == 0x08) { midiFader = d2 / 127f; Engine.setFader(faderOpen()); return }
            if (sig == s.learnJog) { val steps = if (kotlin.math.abs(d2 - 64) < 32) d2 - 64 else if (d2 < 64) d2 else d2 - 128; jog(steps, t); return }
            if (sig == s.learnFader) { midiFader = if (kind == 0xe0) ((d2 shl 7) or d1) / 16383f else d2 / 127f; Engine.setFader(faderOpen()) }
        }
        private fun jog(steps: Int, t: Long) {
            if (!hasJog) Handler(Looper.getMainLooper()).post { hasJog = true; resolve() }
            if (discActive != "midi" || demo) return
            if (!touching) { touching = true }
            lastJog = t
            moveDisc(steps.toDouble() / Store.set.jogSteps * (REV / UNIT) * Store.set.sens, t)
        }
    }

    private fun seenXfader() { if (!hasXfader) Handler(Looper.getMainLooper()).post { hasXfader = true; resolve() } }

    // Monitor MIDI: los últimos mensajes que llegaron, para ver en Ajustes qué manda la controladora
    val midiLog = mutableStateListOf<String>()
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var monPosted = false
    private fun monitor(dev: String, st: Int, d1: Int, d2: Int) {
        val kind = when (st and 0xf0) { 0x90 -> "nota on"; 0x80 -> "nota off"; 0xb0 -> "CC"; 0xe0 -> "pitch"; else -> "%02X".format(st) }
        pending.add("$dev · $kind · canal ${(st and 0x0f) + 1} · %02X · %d".format(d1, d2))
        while (pending.size > 12) pending.poll()
        if (!monPosted) {
            monPosted = true
            Handler(Looper.getMainLooper()).postDelayed({
                monPosted = false
                while (true) { val m = pending.poll() ?: break; midiLog.add(0, m) }
                while (midiLog.size > 10) midiLog.removeAt(midiLog.size - 1)
            }, 100)
        }
    }

    // ---------- DVS ----------
    private fun isUsb(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    private var amRef: AudioManager? = null

    /** Salida: la elegida en Ajustes o, en Auto, la primera USB (la REV1, una placa). */
    fun applyOutput() {
        val am = amRef ?: return
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isUsb(it) }
        audioOutputs.clear(); usb.forEach { audioOutputs.add(AudioInput(it.id, it.productName?.toString() ?: "USB")) }
        val want = Store.set.outDevice
        val dev = when {
            want == -1 -> null
            want > 0 -> usb.firstOrNull { it.id == want } ?: usb.firstOrNull()
            else -> usb.firstOrNull()
        }
        val id = dev?.id ?: 0
        outName = dev?.productName?.toString() ?: "Teléfono"
        if (id != outId) { outId = id; Engine.nSetOutput(id, chOf(dev)) }
        refreshOutInfo()
    }
    private var outName = "Teléfono"
    private fun chOf(d: AudioDeviceInfo?) = d?.channelCounts?.maxOrNull()?.takeIf { it > 0 } ?: 2
    /** Qué abrió de verdad el motor (puede no ser lo pedido si la placa no aceptó el formato). */
    fun refreshOutInfo() {
        val v = IntArray(4); Engine.nOutInfo(v)
        outActive = when {
            v[0] < 0 -> "No se pudo abrir la salida"
            outId > 0 && v[0] != outId -> "No se pudo abrir $outName: suena por el teléfono"
            else -> "$outName · ${v[1]} canales · ${v[2] / 1000.0} kHz" + if (v[3] == 1) " · exclusivo" else ""
        }
    }
    fun preferredOutput(ctx: Context): Pair<Int, Int> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Store.set.outDevice == -1) return 0 to 2
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isUsb(it) }
        val d = usb.firstOrNull { it.id == Store.set.outDevice } ?: usb.firstOrNull()
        outId = d?.id ?: 0
        outName = d?.productName?.toString() ?: "Teléfono"
        return outId to chOf(d)
    }

    fun initAudioInputs(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        amRef = am
        fun refresh() {
            audioInputs.clear()
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
                .forEach { audioInputs.add(AudioInput(it.id, (it.productName?.toString() ?: "USB") + " · " + it.channelCounts.maxOrNull().let { c -> if (c == null || c == 0) "estéreo" else "$c canales" })) }
        }
        refresh()
        applyOutput()
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>) { refresh(); applyOutput(); reopenDvs() }
            override fun onAudioDevicesRemoved(a: Array<out AudioDeviceInfo>) { refresh(); applyOutput(); reopenDvs() }
        }, Handler(Looper.getMainLooper()))
    }

    var hasMicPermission = false
    fun reopenDvs() {
        val s = Store.set
        Engine.nDvsConfig(s.dvsThreshold, 1000f, s.dvsInvert)
        val want = s.dvsDevice != 0 && hasMicPermission && audioInputs.any { it.id == s.dvsDevice }
        dvsOpen = if (want) Engine.nOpenInput(s.dvsDevice) else { Engine.nOpenInput(-1); false }
    }
}
