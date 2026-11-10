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
    var dvsOpen by mutableStateOf(false)
    val dvsState = FloatArray(5)  // velocidad, nivel, señal, calidad, abierta
    @Volatile private var lastSignal = 0L

    fun cueSec() = Store.curSound()?.cue ?: 0.03

    fun faderOpen(): Boolean {
        val s = Store.set
        return when (faderActive) {
            "midi" -> {
                val x = midiFader.coerceIn(0f, 1f)
                val lag = s.xfaderCutLag
                val v = if (s.xfaderSide == "left") 1f - x else x
                val o = v > lag
                if (s.hamster) !o else o
            }
            "dvs" -> dvsState[2] > 0.5f
            else -> if (s.faderMode == "pad") padOpen else {
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
        else Engine.nSetSources(if (disc == "dvs") 1 else 0, if (fader == "dvs") 1 else 0)
    }

    /** Llamar en cada cuadro. */
    fun tick() {
        if (dvsOpen) {
            Engine.nDvsState(dvsState)
            if (dvsState[2] > 0.5f) lastSignal = SystemClock.uptimeMillis()
        }
        val now = SystemClock.uptimeMillis()
        if (!demo) {
            if (Store.set.motor && !touching && discActive != "dvs") {
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
        Engine.setTarget(cueSec() + p * UNIT, vel * UNIT)
        Engine.setFader(faderOpen())
    }

    /** dp en unidades; t en milisegundos (uptime). */
    fun moveDisc(dp: Double, t: Long) {
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
            // REV1: jog en CC 0x21/0x22 de los canales 1 y 2, toque en la nota 0x36; crossfader 14 bits en CC 0x1F/0x3F del canal 7
            if (isRev1) {
                if (kind == 0xb0 && (st == 0xb0 || st == 0xb1) && (d1 == 0x22 || d1 == 0x21)) { jog(d2 - 64, t); return }
                if ((st == 0x90 || st == 0x91) && d1 == 0x36) { touching = d2 > 0; lastJog = if (d2 > 0) t else 0; if (d2 == 0) { vel = 0.0; send() }; return }
                if (st == 0xb6 && d1 == 0x3f) { lsb = d2; return }
                if (st == 0xb6 && d1 == 0x1f) { midiFader = ((d2 shl 7) or lsb) / 16383f; Engine.setFader(faderOpen()); return }
            }
            if (isMixtour && st == 0xb0 && d1 == 0x08) { midiFader = d2 / 127f; Engine.setFader(faderOpen()); return }
            if (sig == s.learnJog) { val steps = if (kotlin.math.abs(d2 - 64) < 32) d2 - 64 else if (d2 < 64) d2 else d2 - 128; jog(steps, t); return }
            if (sig == s.learnFader) { midiFader = if (kind == 0xe0) ((d2 shl 7) or d1) / 16383f else d2 / 127f; Engine.setFader(faderOpen()) }
        }
        private fun jog(steps: Int, t: Long) {
            if (discActive != "midi" || demo) return
            if (!touching) { touching = true }
            lastJog = t
            moveDisc(steps.toDouble() / Store.set.jogSteps * (REV / UNIT) * Store.set.sens, t)
        }
    }

    // ---------- DVS ----------
    fun initAudioInputs(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun refresh() {
            audioInputs.clear()
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
                .forEach { audioInputs.add(AudioInput(it.id, (it.productName?.toString() ?: "USB") + " · " + it.channelCounts.maxOrNull().let { c -> if (c == null || c == 0) "estéreo" else "$c canales" })) }
        }
        refresh()
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>) { refresh(); reopenDvs() }
            override fun onAudioDevicesRemoved(a: Array<out AudioDeviceInfo>) { refresh(); reopenDvs() }
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
