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
    private var lastNs = 0L          // último evento del disco (ns, reloj de System.nanoTime)
    @Volatile var demo = false

    // historia reciente del disco para estimar la velocidad por regresión
    private const val HN = 64
    private const val VEL_WIN = 15_000_000L   // 15 ms
    private val hT = LongArray(HN)
    private val hP = DoubleArray(HN)
    private var hN = 0
    private var hHead = 0
    private fun hClear() { hN = 0; hHead = 0 }
    private fun hPush(t: Long, pos: Double) { hT[hHead] = t; hP[hHead] = pos; hHead = (hHead + 1) % HN; if (hN < HN) hN++ }

    /** Pendiente (unidades/s) de los puntos de los últimos 15 ms. */
    private fun regress(tEnd: Long): Double {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var m = 0
        for (j in 0 until hN) {
            val i = (hHead - 1 - j + HN) % HN
            val age = tEnd - hT[i]
            if (age > VEL_WIN && m >= 2) break
            val x = -age / 1e9; val y = hP[i]
            sx += x; sy += y; sxx += x * x; sxy += x * y; m++
            if (age > VEL_WIN) break   // uno de afuera de la ventana si hacía falta para tener dos
        }
        if (m < 2) return 0.0
        val d = m * sxx - sx * sx
        if (d <= 1e-14) return vel
        return (m * sxy - sx * sy) / d
    }

    // tanda de puntos de un evento táctil (se arma en el hilo principal)
    val batchDp = DoubleArray(256)
    val batchT = LongArray(256)

    // modo teléfono sin controladora: el fader es el botón de corte
    @Volatile var phoneMode = false

    // fader
    @Volatile var fv = 0f            // posición del fader en pantalla 0..1
    @Volatile var padOpen = false
    @Volatile var midiFader = -1f    // crossfader MIDI 0..1 (-1: nunca llegó)
    @Volatile var jogLeft = false    // el último jog que se tocó es de un deck izquierdo (1 o 3); sin jog, como antes (derecha)
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

    // motor del plato: arranca y frena con rampa, como un plato real (Start/Stop)
    @Volatile var motorSpd = 0.0
    private var lastTick = 0L
    private const val MOTOR_UP = 0.35     // s de 0 a 33⅓
    private const val MOTOR_DOWN = 0.5

    /** Start/Stop del plato: prende o apaga el motor (el mismo ajuste de Disco en pantalla › Motor). */
    fun toggleMotor() { Store.set.motor = !Store.set.motor; Store.save() }

    // controles de la REV1 (los atiende la práctica; llegan en el hilo principal)
    var onPlay: (() -> Unit)? = null
    var onShiftPlay: (() -> Unit)? = null
    var onPad: ((Int, Boolean) -> Unit)? = null
    @Volatile var shiftHeld = false

    /** Posición del disco en segundos de sonido. */
    fun posSec() = cueSec() + p * UNIT

    /** Salta a un punto del sonido (cue 1-4). Para atrás frena en el cue. */
    @Synchronized fun jumpTo(sec: Double) {
        if (autoDisc) return
        val s = Store.curSound() ?: return
        val x = sec.coerceIn(s.cue, s.end)
        p = (x - s.cue) / UNIT
        hClear(); if (touching) hPush(System.nanoTime(), p)
        Engine.nJump(x)
        send()
    }

    /** Cue i del sonido: si está, salta; con [store], lo guarda en la posición actual. */
    fun hotCue(i: Int, store: Boolean) {
        val s = Store.curSound() ?: return
        if (store) { Store.setCue(s.key, i, posSec().coerceIn(s.cue, s.end)); return }
        val c = Store.cuesOf(s.key).getOrNull(i) ?: return
        if (!c.isNaN()) jumpTo(c)
    }

    fun faderOpen(): Boolean {
        val s = Store.set
        if (autoFader) return autoFaderOpen
        return when (faderActive) {
            "midi" -> {
                val x = midiFader.coerceIn(0f, 1f)
                val lag = s.xfaderCutLag
                val left = when (s.xfaderSide) { "left" -> true; "right" -> false; else -> jogLeft }
                val v = if (left) 1f - x else x
                val o = v > lag
                if (s.hamster) !o else o
            }
            "dvs" -> dvsState[2] > 0.5f
            else -> if (phoneMode) (if (s.holdCuts) !padOpen else padOpen)
            else if (s.faderMode == "pad") padOpen else if (s.faderMode == "cut") !padOpen else {
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
        val now = System.nanoTime()
        if (!demo) synchronized(this) {
            val dt = (now - lastNs).coerceIn(0, 50_000_000) / 1e9
            // la rampa del motor corre aunque tengas el disco agarrado (el plato sigue girando abajo)
            val rdt = (now - lastTick).coerceIn(0, 50_000_000) / 1e9; lastTick = now
            val want = if (Store.set.motor && discActive != "dvs") 1.0 else 0.0
            val was = motorSpd
            motorSpd = if (want > motorSpd) kotlin.math.min(want, motorSpd + rdt / MOTOR_UP) else kotlin.math.max(want, motorSpd - rdt / MOTOR_DOWN)
            if (autoDisc) { /* el disco lo mueve la app */ }
            else if (motorSpd > 0 && !touching && discActive != "dvs") {
                p += dt * motorSpd / UNIT; vel = motorSpd / UNIT; lastNs = now; send(now)
            } else if (was > 0 && motorSpd == 0.0 && !touching && discActive != "dvs") { vel = 0.0; lastNs = now; send(now) }
            if (lastJog != 0L && now - lastJog > 80_000_000) { lastJog = 0; touching = false; vel = 0.0; hClear(); send(now) }
            if (!touching && motorSpd == 0.0) lastNs = now
        }
        resolve()
        if (!demo && discActive != "dvs") Engine.setFader(faderOpen())
    }

    fun send() { send(System.nanoTime()) }
    private fun send(tNs: Long) {
        if (demo) return
        if (!autoDisc) Engine.setTarget(cueSec() + p * UNIT, vel * UNIT, tNs)
        Engine.setFader(faderOpen())
    }

    /**
     * Posición física del fader para dibujar (0 = izquierda, 1 = derecha), sea cual sea la fuente.
     * Abierto queda a la derecha en normal y a la izquierda en hamster.
     */
    fun faderKnob(): Float {
        val s = Store.set
        val openRight = !s.hamster
        fun side(open: Boolean) = if (open == openRight) 1f else 0f
        if (autoFader) return side(autoFaderOpen)
        return when (faderActive) {
            "midi" -> midiFader.coerceIn(0f, 1f)
            "dvs" -> side(dvsState[2] > 0.5f)
            else -> if (s.faderMode == "slide" && !phoneMode) fv else side(faderOpen())
        }
    }

    /**
     * Una tanda de movimiento (todos los puntos de un evento): dps en unidades, ts en ns
     * (tiempo real de cada punto). Manda un solo objetivo al motor, con la velocidad de la
     * regresión de los últimos 15 ms.
     */
    private val batchPos = DoubleArray(256)
    @Synchronized fun moveDisc(dps: DoubleArray, ts: LongArray, n: Int) {
        if (autoDisc || n <= 0) return
        val cue = cueSec()
        val m = kotlin.math.min(n, 256)
        for (k in 0 until m) {
            p = kotlin.math.max(0.0, p + dps[k])   // para atrás frena en el cue
            hPush(ts[k], p)
            batchPos[k] = cue + p * UNIT
        }
        lastNs = ts[m - 1]
        vel = regress(lastNs)
        // el motor recibe todos los puntos con su tiempo y reproduce la trayectoria
        if (!demo) { Engine.pushPoints(ts, batchPos, m, vel * UNIT); Engine.setFader(faderOpen()) }
    }
    private val one = DoubleArray(1)
    private val oneT = LongArray(1)
    @Synchronized fun moveDisc(dp: Double, tNs: Long) { one[0] = dp; oneT[0] = tNs; moveDisc(one, oneT, 1) }

    @Synchronized fun touchDisc(down: Boolean, tNs: Long) {
        touching = down; vel = 0.0; lastNs = tNs; hClear()
        if (down) hPush(tNs, p)
        send(tNs)
    }
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

    private val main = Handler(Looper.getMainLooper())

    private class Parser(val name: String) : MidiReceiver() {
        private var running = 0
        private val isRev1 = name.contains("REV1", true) || name.contains("DDJ", true)
        private val isMixtour = name.contains("Mixtour", true)
        private val buf = IntArray(3); private var n = 0
        private var ts = 0L
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            // el sello del mensaje está en el reloj de System.nanoTime
            val now = System.nanoTime()
            ts = if (timestamp > 0 && timestamp <= now && now - timestamp < 200_000_000) timestamp else now
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
            val t = ts
            monitor(name, st, d1, d2)
            val ch = st and 0x0f
            // DDJ de Pioneer (REV1), como el mapeo de Cutlab: jog del plato en CC 0x22 (modo vinilo) / 0x23,
            // borde en 0x21, centro en 64 (valor - 64); toque en la nota 0x36; crossfader 14 bits en
            // CC 0x1F (MSB) / 0x3F (LSB) del canal 7. En los canales de deck, CC 0x1F es SHIFT + jog: se ignora.
            if (isRev1) {
                if (kind == 0xb0 && ch <= 3 && (d1 == 0x21 || d1 == 0x22 || d1 == 0x23)) {
                    jogLeft = ch == 0 || ch == 2
                    jog(d2 - 64, t); return
                }
                if (kind == 0xb0 && ch <= 3 && d1 == 0x1f) return
                if ((kind == 0x90 || kind == 0x80) && ch <= 3 && d1 == 0x36) {
                    val on = kind == 0x90 && d2 > 0
                    if (on) jogLeft = ch == 0 || ch == 2
                    lastJog = if (on) t else 0; touchDisc(on, t); return
                }
                // SHIFT (nota 0x3F), PLAY/PAUSE (0x0B; con SHIFT, 0x47) y pads en Hot Cue (canales 8-11: notas 0-7, con SHIFT 8-15)
                if ((kind == 0x90 || kind == 0x80) && ch <= 3 && d1 == 0x3f) { shiftHeld = kind == 0x90 && d2 > 0; return }
                if (kind == 0x90 && d2 > 0 && ch <= 3 && (d1 == 0x0b || d1 == 0x47)) {
                    val sh = d1 == 0x47 || shiftHeld
                    main.post { if (sh) onShiftPlay?.invoke() else onPlay?.invoke() }
                    return
                }
                if ((kind == 0x90 || kind == 0x80) && ch <= 3 && (d1 == 0x0b || d1 == 0x47)) return
                if (kind == 0x90 && ch in 7..10 && d1 < 16) {
                    if (d2 > 0 && (d1 and 7) < 4) { val i = d1 and 7; val sh = d1 >= 8 || shiftHeld; main.post { onPad?.invoke(i, sh) } }
                    return
                }
                if (kind == 0x80 && ch in 7..10 && d1 < 16) return
                if (kind == 0xb0 && ch == 6 && d1 == 0x3f) { lsb = d2; return }
                if (kind == 0xb0 && ch == 6 && d1 == 0x1f) { midiFader = ((d2 shl 7) or lsb) / 16383f; seenXfader(); Engine.setFader(faderOpen()); return }
            }
            if (isMixtour && st == 0xb0 && d1 == 0x08) { midiFader = d2 / 127f; Engine.setFader(faderOpen()); return }
            if (sig == s.learnJog) { val steps = if (kotlin.math.abs(d2 - 64) < 32) d2 - 64 else if (d2 < 64) d2 else d2 - 128; jog(steps, t); return }
            if (sig == s.learnFader) { midiFader = if (kind == 0xe0) ((d2 shl 7) or d1) / 16383f else d2 / 127f; Engine.setFader(faderOpen()) }
        }
        private fun jog(steps: Int, t: Long) {
            if (!hasJog) Handler(Looper.getMainLooper()).post { hasJog = true; resolve() }
            if (discActive != "midi" || demo) return
            if (!touching) touchDisc(true, t)
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
