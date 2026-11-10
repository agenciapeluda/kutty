package com.agenciapeluda.kutty.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agenciapeluda.kutty.audio.Engine
import com.agenciapeluda.kutty.audio.Inputs
import com.agenciapeluda.kutty.audio.UNIT
import com.agenciapeluda.kutty.data.SessionRec
import com.agenciapeluda.kutty.data.Store
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Flash(val b: Double, val grade: Int, val at: Long)

class Result(val rec: SessionRec, val feedback: List<Pair<Int, String>>, val timed: List<Timed>, val total: Double)

/** La práctica: plan de trazos, reloj de tiempos, demo, calificación y cierre. */
object Session {
    val TOL = mapOf("facil" to 90, "normal" to 60, "pro" to 35)
    val MODE_MULT = mapOf("aprender" to 0.5, "practicar" to 1.0, "desafio" to 1.5)
    val GRADE_PTS = intArrayOf(100, 70, 30, 0)

    var tech by mutableStateOf<Tech?>(null)
    var mode by mutableStateOf("practicar")
    var part by mutableStateOf("completo")   // completo | plato (la app hace el fader) | fader (la app mueve el disco)
    var bpmNote by mutableStateOf<String?>(null)
    var bpm by mutableIntStateOf(85)
    var running by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var demoOnly = false
    var result by mutableStateOf<Result?>(null)

    // HUD (se actualiza ~10 veces por segundo)
    var hudPts by mutableIntStateOf(0)
    var hudCombo by mutableIntStateOf(0)
    var hudAcc by mutableIntStateOf(-1)
    var hudState by mutableStateOf("Libre: scratcheá y mirá tu trazo")
    var hudCount by mutableStateOf(false)
    var challengeBpm by mutableIntStateOf(85)

    // estado por cuadro
    class Seg(val frame: Double, val beat: Double, val bpm: Double)
    private val segs = ArrayList<Seg>()
    var timed: List<Timed> = emptyList(); private set
    val samples = Samples()
    val graded = ArrayList<Timed>()
    val flash = ArrayList<Flash>()
    var total = 0.0; private set
    private var nextGrade = 0
    private var combo = 0
    private var maxCombo = 0
    private var pts = 0
    private var clickNext = -4
    private var blocks = 0
    private var blockBeats = 16
    private var t0 = 0L
    private var beatBase = 88.0
    var heardBeat = 0.0; private set
    private var lastHud = 0L

    private val dFrames = LongArray(8192); private val dPos = FloatArray(8192); private val dF = ByteArray(8192)

    private val sr get() = Engine.sampleRate.toDouble()

    fun beatAt(frame: Double): Double {
        if (segs.isEmpty()) return frame / sr * bpm / 60.0
        var k = 0
        for (i in segs.indices) if (frame >= segs[i].frame) k = i
        val s = segs[k]
        return s.beat + (frame - s.frame) / sr * s.bpm / 60.0
    }
    fun frameAt(b: Double): Double {
        var k = 0
        for (i in segs.indices) if (b >= segs[i].beat) k = i
        val s = segs[k]
        return s.frame + (b - s.beat) * 60.0 / s.bpm * sr
    }
    fun bpmAt(b: Double): Double { var k = 0; for (i in segs.indices) if (b >= segs[i].beat) k = i; return if (segs.isEmpty()) bpm.toDouble() else segs[k].bpm }

    fun open(t: Tech, m: String) {
        stop(true)
        tech = t; mode = m; ended = false; result = null
        val b = Store.curBeat()
        bpm = if (b != null && !b.builtin) b.bpm.roundToInt() else t.bpm
        timed = emptyList(); graded.clear(); samples.clear(); flash.clear()
        hudPts = 0; hudCombo = 0; hudAcc = -1
    }

    private fun plan() {
        val t = tech ?: return
        val unit = max(4, t.beats).toDouble()
        val list = ArrayList<Timed>()
        val beats: Double
        when {
            demoOnly -> {
                beats = max(8, t.beats * 2).toDouble()
                var b = 0.0; while (b < beats) { list += Techniques.timeStrokes(t.strokes, b, true); b += t.beats }
            }
            mode == "aprender" -> {
                beats = unit * 4
                for (blk in 0 until 4) { var b = 0.0; while (b < unit) { list += Techniques.timeStrokes(t.strokes, blk * unit + b, blk % 2 == 0); b += t.beats } }
            }
            mode == "desafio" -> {
                blockBeats = max(16, t.beats * 2)
                beats = blockBeats * 10.0
                var b = 0.0; while (b < beats) { list += Techniques.timeStrokes(t.strokes, b); b += t.beats }
            }
            else -> {
                var bb = max(Store.set.bars * 4, t.beats).toDouble()
                bb = ceil(bb / t.beats) * t.beats
                beats = bb
                var b = 0.0; while (b < beats) { list += Techniques.timeStrokes(t.strokes, b); b += t.beats }
            }
        }
        timed = list; total = beats
    }

    fun start(demo: Boolean) {
        val beat = Store.curBeat() ?: return
        stop(true)
        demoOnly = demo
        plan()
        samples.clear(); graded.clear(); flash.clear()
        nextGrade = 0; combo = 0; maxCombo = 0; pts = 0; ended = false; blocks = 0; result = null
        challengeBpm = bpm
        Inputs.resetDisc()
        Inputs.autoFader = part == "plato" && !demo
        Inputs.autoDisc = part == "fader" && !demo
        Inputs.autoFaderOpen = false
        Inputs.resolve()
        Engine.nSetKeylock(Store.set.keylock)
        bpmNote = null
        beatBase = beat.bpm
        val f0 = Engine.nWritten() + 0.2 * sr
        segs.clear(); segs.add(Seg(f0, -4.0, bpm.toDouble()))
        Engine.nStartBeat(f0.toLong(), beat.start, bpm / beat.bpm, beat.loop, beat.loopLen())
        clickNext = -4
        running = true
        t0 = System.currentTimeMillis()
    }

    fun stop(silent: Boolean) {
        if (!running) return
        running = false
        Engine.nStopBeat()
        Inputs.demo = false
        releaseAuto()
        if (!silent) finish(true)
        segs.clear()
    }

    private fun releaseAuto() {
        if (Inputs.autoFader || Inputs.autoDisc) { Inputs.autoFader = false; Inputs.autoDisc = false; Inputs.resolve(); Inputs.send() }
    }

    /** Un cuadro de pantalla. */
    fun tick() {
        Inputs.tick()
        drain()
        val heardFrame = Engine.nHeard()
        heardBeat = if (running || ended) beatAt(heardFrame) else heardFrame / sr * bpm / 60.0
        if (running) runTick()
        val now = System.currentTimeMillis()
        if (now - lastHud > 90) { lastHud = now; hud() }
    }

    private fun drain() {
        val cue = Inputs.cueSec()
        val calibFrames = Store.set.calib / 1000.0 * sr
        while (true) {
            val n = Engine.nDrain(dFrames, dPos, dF)
            if (n <= 0) break
            for (i in 0 until n) {
                val p = ((dPos[i] - cue) / UNIT).toFloat()
                if (Inputs.discActive == "dvs" && !Inputs.demo) Inputs.p = p.toDouble()
                if (Inputs.demo || ended) continue
                val b = if (running) beatAt(dFrames[i] - calibFrames) else dFrames[i] / sr * bpm / 60.0
                samples.add(b, p, dF[i])
            }
            if (n < dFrames.size) break
        }
    }

    private fun runTick() {
        val written = Engine.nWritten().toDouble()
        // metrónomo
        while (frameAt(clickNext.toDouble()) < written + 0.25 * sr) {
            val b = clickNext
            if (b < total && (b < 0 || Store.set.metro)) Engine.nClick(frameAt(b.toDouble()).toLong(), ((b % 4) + 4) % 4 == 0)
            clickNext++
        }
        val schedB = beatAt(written)
        val cur = timed.firstOrNull { schedB >= it.b0 && schedB < it.b1 }
        val demoNow = cur?.demo == true
        if (demoNow) {
            if (!Inputs.demo) { Inputs.demo = true; Inputs.resolve() }
            val r = Grading.refAt(timed, schedB)
            val cue = Inputs.cueSec()
            Engine.nSetTarget(cue + r.p * UNIT, r.v * UNIT * bpmAt(schedB) / 60)
            Engine.setFader(r.f == 1)
            Inputs.p = r.p
        } else if (Inputs.demo) {
            Inputs.demo = false; Inputs.p = 0.0; Inputs.vel = 0.0; Inputs.resolve(); Inputs.send()
        }
        // por partes: la app hace la otra mano, perfecta
        if (!demoNow && (Inputs.autoFader || Inputs.autoDisc)) {
            val r = if (schedB >= 0) Grading.refAt(timed, schedB) else null
            if (Inputs.autoFader) { val o = r?.f == 1; if (o != Inputs.autoFaderOpen) { Inputs.autoFaderOpen = o; Engine.setFader(o) } }
            if (Inputs.autoDisc) {
                val p = r?.p ?: 0.0
                Engine.nSetTarget(Inputs.cueSec() + p * UNIT, (r?.v ?: 0.0) * UNIT * bpmAt(schedB) / 60)
                Inputs.p = p
            }
        }
        val heard = heardBeat
        val tolMs = TOL[if (mode == "aprender") "facil" else Store.set.diff] ?: 60
        val tolB = tolMs / 1000.0 * bpmAt(heard) / 60
        val t = tech!!
        while (nextGrade < timed.size) {
            val s = timed[nextGrade]
            if (heard < s.b1 + tolB + 0.08) break
            if (!s.demo) {
                val g = Grading.grade(s, samples, tolB, 0.014 * bpmAt(s.b0) / 60, if (demoOnly) "completo" else part)
                s.g = g
                graded.add(s)
                if (g.grade <= Grade.GOOD) combo++ else combo = 0
                maxCombo = max(maxCombo, combo)
                val mult = min(3.0, 1 + floor(combo / 8.0) * 0.5)
                pts += (GRADE_PTS[g.grade] * mult * (MODE_MULT[if (demoOnly) "aprender" else mode] ?: 1.0) * (1 + t.level * 0.1)).roundToInt()
                flash.add(Flash((s.b0 + s.b1) / 2, g.grade, System.currentTimeMillis()))
                if (flash.size > 24) flash.removeAt(0)
            }
            nextGrade++
        }
        if (mode == "desafio" && !demoOnly) {
            val next = (blocks + 1) * blockBeats.toDouble()
            if (schedB > next - 1 && next < total) {
                val blk = graded.filter { it.b0 >= next - blockBeats && it.b0 < next }
                val acc = if (blk.isEmpty()) 0.0 else blk.sumOf { it.g!!.final } / blk.size
                blocks++
                if (acc >= 0.65) {
                    challengeBpm += 4
                    val f = frameAt(next)
                    segs.add(Seg(f, next, challengeBpm.toDouble()))
                    Engine.nRate(f.toLong(), challengeBpm / beatBase)
                    hudState = "Bloque superado · $challengeBpm BPM"
                } else {
                    total = next
                    timed = timed.filter { it.b0 < next }
                }
            }
        }
        if (heard > total + 0.6 && nextGrade >= timed.size) finish(false)
    }

    private fun finish(aborted: Boolean) {
        if (running) { running = false; Engine.nStopBeat() }
        Inputs.demo = false
        releaseAuto()
        ended = true
        val t = tech ?: return
        if (demoOnly || graded.isEmpty()) { ended = false; segs.clear(); return }
        val acc = graded.sumOf { it.g!!.final } / graded.size
        val counts = IntArray(4); graded.forEach { counts[it.g!!.grade]++ }
        val dur = max(1, ((System.currentTimeMillis() - t0) / 1000).toInt())
        val rec = SessionRec(System.currentTimeMillis(), t.id, mode, if (mode == "desafio") challengeBpm else bpm, (acc * 100).roundToInt(), pts, dur, counts, maxCombo, aborted)
        Store.sessions.add(rec)
        // la técnica se domina con las dos manos
        if (mode != "aprender" && !aborted && part == "completo") Store.best[t.id] = max(Store.best[t.id] ?: 0, rec.acc)
        Store.save()
        val fb = ArrayList(Grading.feedback(graded, samples, rec.bpm.toDouble(), acc, mode == "aprender", part))
        // BPM progresivo: si lo pasaste bien, la próxima va un poco más rápido
        val s = Store.set
        if (s.progressive && !aborted && mode == "practicar" && acc >= 0.85 && bpm < s.progMax) {
            bpm = min(s.progMax, bpm + s.progStep); bpmNote = "Subió a $bpm BPM"
            fb.add(0, 0 to "Pasaste el ejercicio: la próxima va a $bpm BPM.")
        }
        result = Result(rec, fb, timed, total)
        hud()
    }

    fun closeResult() { result = null; ended = false; segs.clear() }

    private fun hud() {
        hudPts = pts; hudCombo = combo
        hudAcc = if (graded.isEmpty()) -1 else (graded.sumOf { it.g!!.final } / graded.size * 100).roundToInt()
        val now = heardBeat
        hudCount = running && now < 0
        if (running) {
            if (now < 0) hudState = ceil(-now).toInt().toString()
            else if (!hudState.startsWith("Bloque") || (now % blockBeats) > 3) {
                val cur = timed.firstOrNull { now >= it.b0 && now < it.b1 }
                hudState = when {
                    demoOnly || cur?.demo == true -> "Escuchá"
                    mode == "aprender" -> "Tu turno"
                    else -> "Compás ${floor(now / 4).toInt() + 1} / ${ceil(total / 4).toInt()}"
                }
            }
        } else hudState = if (ended) "Terminado" else "Libre: scratcheá y mirá tu trazo"
    }
}
