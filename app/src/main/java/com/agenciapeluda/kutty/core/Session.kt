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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Flash(val b: Double, val grade: Int, val at: Long)

/** Libre: cómo se lee el tiempo musical desde x (reloj de la sesión). on = el beat suena. */
class GridSeg(val x0: Double, val shift: Double, val on: Boolean)

/** Animación de Libre: tajo de un truco, estrella de un corte, golpe de combo. */
class NinjaEv(val kind: Int, val x0: Double, val x1: Double, val p0: Double, val p1: Double, val label: String, val at: Long, val q: Int = 0) {
    companion object { const val TAJO = 0; const val STAR = 1; const val COMBO = 2; const val FINAL = 3 }
}

/** Lectura al parar Libre. */
class LibreRead(
    val rec: SessionRec, val counts: List<Pair<String, Int>>, val tricks: Int, val cpsAvg: Double, val cpsPeak: Int,
    val cuts: Int, val cleanPct: Int?, val gridPct: Int?, val gridType: String?, val bestCombo: Int,
)

class Result(val rec: SessionRec, val feedback: List<Pair<Int, String>>, val timed: List<Timed>, val total: Double, val guide: Curve, val tolB: Double)

/** La práctica: plan de trazos, reloj de tiempos, demo, calificación y cierre. */
object Session {
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
    /** La guía (curva humana ideal) y cómo toca la app (demo y manos automáticas, según Ajustes › Demo). */
    var guide: Curve = Curve.EMPTY; private set
    var perf: Curve = Curve.EMPTY; private set
    private val lag = LagTracker()
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

    // ---------- Libre ----------
    val libre get() = mode == "libre" && !demoOnly
    var beatPaused by mutableStateOf(false)
    val gridSegs = ArrayList<GridSeg>()
    private var shift = 0.0          // reloj − tiempo musical
    private var pausedM = 0.0
    private var pausedSec = 0.0
    private var beatEdited = false
    /** Se movió el 1 de la pista en Libre (para avisar que los ejercicios usan la grilla nueva). */
    var gridMoved = false
    val tracker = TrickTracker()
    val tricks = ArrayList<Trick>()
    val ninja = ArrayList<NinjaEv>()
    private val cutT = ArrayList<Double>()
    private val cutQ = IntArray(3)
    private val active = HashSet<Long>()
    private var lastP = 0f; private var lastF: Byte = -1
    private var lastPoll = 0L
    private var chainLast: Trick? = null
    private var chainN = 0; private var repN = 0
    var bestCombo = 0; private set
    var hudTricks by mutableIntStateOf(0)
    var hudCps by mutableStateOf(0.0)
    var libreRead by mutableStateOf<LibreRead?>(null)
    private val cutWatch = CutWatch { t, x, p, q -> onCut(t, x, p, q) }

    /** Tiempo musical en x (null si el beat estaba en pausa). */
    fun musical(x: Double): Double? {
        for (i in gridSegs.indices.reversed()) { val g = gridSegs[i]; if (x >= g.x0) return if (g.on) x - g.shift else null }
        return x
    }

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
        tech = t; mode = m; ended = false; result = null; libreRead = null
        val b = Store.curBeat()
        bpm = if (b != null && !b.builtin) b.bpm.roundToInt() else t.bpm
        timed = emptyList(); guide = Curve.EMPTY; perf = Curve.EMPTY; graded.clear(); samples.clear(); flash.clear()
        hudPts = 0; hudCombo = 0; hudAcc = -1
    }

    private fun plan() {
        val t = tech ?: return
        val unit = max(4, t.beats).toDouble()
        val list = ArrayList<Timed>()
        val beats: Double
        when {
            mode == "libre" && !demoOnly -> {
                // libre: la pista sigue hasta que pares, sin ejercicio ni puntaje
                beats = 1e9
            }
            demoOnly -> {
                // Escuchar: dos compases al BPM elegido, plato y fader automáticos
                beats = 8.0
                var b = 0.0; while (b < beats) { list += Techniques.timeStrokes(t.strokes, b, true).filter { it.b0 < beats }; b += t.beats }
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
        guide = Human.ideal(list, bpm.toDouble())
        perf = Human.perform(list, bpm.toDouble(), Store.set.demo, System.nanoTime())
    }

    /** Tolerancias de la exigencia actual (Aprender siempre en Fácil). */
    fun tol(b: Double = heardBeat): Tol = Tol.of(if (mode == "aprender" && !demoOnly) "facil" else Store.set.diff, if (running || ended) bpmAt(b) else bpm.toDouble())

    fun start(demo: Boolean) {
        val beat = Store.curBeat() ?: return
        stop(true)
        demoOnly = demo
        plan()
        samples.clear(); graded.clear(); flash.clear(); lag.reset()
        nextGrade = 0; combo = 0; maxCombo = 0; pts = 0; ended = false; blocks = 0; result = null
        challengeBpm = bpm
        Inputs.resetDisc()
        resetLibre()
        Inputs.autoFader = part == "plato" && !demo && mode != "libre"
        Inputs.autoDisc = part == "fader" && !demo && mode != "libre"
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
        script(0.0, false, false)
        if (Inputs.autoFader || Inputs.autoDisc) { Inputs.autoFader = false; Inputs.autoDisc = false; Inputs.resolve(); Inputs.send() }
    }

    // trayectoria programada para el motor (1 punto por ms, 200 ms por delante)
    private const val SCRIPT_STEP = 48L
    private val spF = LongArray(512); private val spP = DoubleArray(512); private val spA = ByteArray(512)
    private var scriptF = -1L
    private var scriptOn = 0

    private fun script(written: Double, disc: Boolean, fader: Boolean) {
        val on = (if (disc) 1 else 0) or (if (fader) 2 else 0)
        if (on == 0) {
            if (scriptOn != 0) { scriptOn = 0; scriptF = -1; Engine.nScript(false, false); Inputs.send() }
            return
        }
        val step = SCRIPT_STEP * sr.toLong() / 48000
        var f = if (scriptF < 0) written.toLong() else scriptF + step
        val until = written + 0.2 * sr
        val cue = Inputs.cueSec()
        var k = 0
        while (f <= until && k < spF.size) {
            val b = beatAt(f.toDouble())
            spF[k] = f; spP[k] = cue + (if (b >= 0) perf.pos(b) else 0.0) * UNIT; spA[k] = (if (b >= 0) perf.fader(b) else 0).toByte()
            scriptF = f; f += step; k++
        }
        if (k > 0) Engine.nPushScript(spF, spP, spA, k)
        if (on != scriptOn) { scriptOn = on; Engine.nScript(disc, fader) }
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
                // en Escuchar el trazo de la demo dibuja la guía
                if ((Inputs.demo && !demoOnly) || ended) continue
                val b = if (running) beatAt(dFrames[i] - calibFrames) else dFrames[i] / sr * bpm / 60.0
                samples.add(b, p, dF[i])
                if (running && libre) {
                    val sec = (dFrames[i] - calibFrames) / sr
                    tracker.add(sec, b, musical(b) ?: Double.NaN, p, dF[i])
                    cutWatch.feed(sec, b, p.toDouble(), dF[i].toInt())
                    if (abs(p - lastP) > 0.002f || dF[i] != lastF) active.add(floor(sec).toLong())
                    lastP = p; lastF = dF[i]
                }
            }
            if (n < dFrames.size) break
        }
    }

    private fun runTick() {
        val written = Engine.nWritten().toDouble()
        if (libre) { libreTick(written); return }
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
        } else if (Inputs.demo) {
            Inputs.demo = false; Inputs.p = 0.0; Inputs.vel = 0.0; Inputs.resolve()
        }
        // demo y por partes: el motor toca la mano de la app programada por cuadro de audio
        val scD = demoNow || Inputs.autoDisc
        val scF = demoNow || Inputs.autoFader
        script(written, scD, scF)
        if (scD) Inputs.p = if (schedB >= 0) perf.pos(schedB) else 0.0
        if (Inputs.autoFader && !demoNow) Inputs.autoFaderOpen = schedB >= 0 && perf.fader(schedB) == 1
        val heard = heardBeat
        val tol = tol(heard)
        val t = tech!!
        while (nextGrade < timed.size) {
            val s = timed[nextGrade]
            // espera lo justo para ver el giro del final (con el desfase general ya medido)
            if (heard < s.b1 + max(0.0, lag.value()) + 2.5 * tol.turnB + 0.04) break
            if (!s.demo) {
                val g = Grading.grade(timed, nextGrade, guide, samples, tol(s.b0), if (demoOnly) "completo" else part, lag)
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
        if (libre) { finishLibre(); segs.clear(); beatPaused = false; return }
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
        val fb = ArrayList(Grading.feedback(graded, rec.bpm.toDouble(), acc, mode == "aprender", part, Tol.of(if (mode == "aprender") "facil" else Store.set.diff, rec.bpm.toDouble())))
        // BPM progresivo: si lo pasaste bien, la próxima va un poco más rápido
        val s = Store.set
        if (s.progressive && !aborted && mode == "practicar" && acc >= 0.85 && bpm < s.progMax) {
            bpm = min(s.progMax, bpm + s.progStep); bpmNote = "Subió a $bpm BPM"
            fb.add(0, 0 to "Pasaste el ejercicio: la próxima va a $bpm BPM.")
        }
        result = Result(rec, fb, timed, total, guide, tol().turnB)
        hud()
    }

    fun closeResult() { result = null; ended = false; segs.clear() }

    private fun hud() {
        if (libre) {
            hudTricks = tricks.size; hudCombo = if (chainN >= 2) chainN else 0
            val tn = if (tracker.trace.n > 0) tracker.trace.t[tracker.trace.n - 1] else 0.0
            var k = 0; for (i in cutT.indices.reversed()) { if (cutT[i] < tn - 1.0) break; k++ }
            hudCps = k.toDouble()
        }
        hudPts = pts; if (!libre) hudCombo = combo
        hudAcc = if (graded.isEmpty()) -1 else (graded.sumOf { it.g!!.final } / graded.size * 100).roundToInt()
        val now = heardBeat
        hudCount = running && now < 0
        if (running) {
            if (now < 0) hudState = ceil(-now).toInt().toString()
            else if (!hudState.startsWith("Bloque") || (now % blockBeats) > 3) {
                val cur = timed.firstOrNull { now >= it.b0 && now < it.b1 }
                hudState = when {
                    mode == "libre" && !demoOnly -> if (beatPaused) "Beat en pausa" else musical(now)?.let { "Libre · compás ${floor((it + 4) / 4).toInt() + 1}" } ?: "Libre"
                    demoOnly || cur?.demo == true -> "Escuchá"
                    mode == "aprender" -> "Tu turno"
                    else -> "Compás ${floor(now / 4).toInt() + 1} / ${ceil(total / 4).toInt()}"
                }
            }
        } else hudState = if (ended) "Terminado" else "Libre: scratcheá y mirá tu trazo"
    }

    // ---------- Libre: beat, trucos, combos y lectura ----------

    private fun resetLibre() {
        gridSegs.clear(); gridSegs.add(GridSeg(-1e9, 0.0, true)); shift = 0.0
        beatPaused = false; beatEdited = false; gridMoved = false
        tracker.reset(); tricks.clear(); ninja.clear(); cutWatch.reset()
        cutT.clear(); cutQ.fill(0); active.clear(); lastF = -1
        chainLast = null; chainN = 0; repN = 0; bestCombo = 0
        hudTricks = 0; hudCps = 0.0; libreRead = null
    }

    private fun libreTick(written: Double) {
        // metrónomo: la cuenta y, si está prendido, los tiempos de la pista
        if (!beatPaused) while (true) {
            val x = clickNext + shift
            val f = frameAt(x)
            if (f >= written + 0.25 * sr) break
            if (f >= written && (x < 0 || Store.set.metro)) Engine.nClick(f.toLong(), ((clickNext % 4) + 4) % 4 == 0)
            clickNext++
        }
        val now = System.currentTimeMillis()
        if (now - lastPoll >= 80) {
            lastPoll = now
            for (k in tracker.poll()) onTrick(k, now)
            // se cortó el flujo: cierra el combo
            val tn = if (tracker.trace.n > 0) tracker.trace.t[tracker.trace.n - 1] else 0.0
            chainLast?.let { if (tn - it.t1 > FLOW) closeChain(now) }
            ninja.removeAll { now - it.at > 2500 }
        }
    }

    private const val FLOW = 0.75   // s entre trucos para seguir el combo

    private fun onTrick(k: Trick, now: Long) {
        tricks.add(k)
        val prev = chainLast
        val flow = prev != null && k.t0 - prev.t1 < FLOW
        if (!flow) { closeChain(now); chainN = 1; repN = 1 }
        else if (k.name != prev!!.name) { chainN++; repN = 1 } else repN++
        chainLast = k
        ninja.add(NinjaEv(NinjaEv.TAJO, k.x0, k.x1, k.pLo, k.pHi, k.name + if (repN > 1) " ×$repN" else "", now))
        if (flow && repN == 1 && chainN >= 2) ninja.add(NinjaEv(NinjaEv.COMBO, k.x0, k.x1, k.pLo, k.pHi, "Combo ×$chainN", now))
        if (ninja.size > 96) ninja.removeAt(0)
    }

    private fun closeChain(now: Long) {
        val last = chainLast
        if (chainN >= 2 && last != null) {
            bestCombo = max(bestCombo, chainN)
            ninja.add(NinjaEv(NinjaEv.FINAL, last.x0, last.x1, last.pLo, last.pHi, "Combo ×$chainN", now))
        }
        chainLast = null; chainN = 0; repN = 0
    }

    private fun onCut(t: Double, x: Double, p: Double, q: Byte) {
        cutT.add(t); cutQ[q.toInt()]++
        // limpio: estrella; con rebote: estrella torcida; sucio: nada
        if (q != CutQ.DIRTY) {
            ninja.add(NinjaEv(NinjaEv.STAR, x, x, p, p, "", System.currentTimeMillis(), q.toInt()))
            if (ninja.size > 96) ninja.removeAt(0)
        }
    }

    /** Play/pausa del beat (solo Libre). Sin la sesión andando, arranca. */
    fun toggleBeat() {
        if (mode != "libre") return
        if (!running) { if (Store.audioReady) start(false); return }
        val b = Store.curBeat() ?: return
        val f = Engine.nWritten() + 0.03 * sr
        val x = beatAt(f)
        if (!beatPaused) {
            pausedM = x - shift
            pausedSec = b.secAt(pausedM) ?: b.start
            Engine.nPauseBeat(f.toLong())
            gridSegs.add(GridSeg(x, shift, false))
            beatPaused = true
        } else {
            if (beatEdited) { restartFrom(b, f, x, pausedSec); beatEdited = false }
            else { shift = x - pausedM; Engine.nResumeBeat(f.toLong()); gridSegs.add(GridSeg(x, shift, true)) }
            clickNext = ceil(x - shift).toInt()
            beatPaused = false
        }
        if (gridSegs.size > 64) gridSegs.removeAt(1)
    }

    /** Sigue la pista desde curSec con el inicio y el largo que tenga ahora (sin volver al principio). */
    private fun restartFrom(b: com.agenciapeluda.kutty.audio.BeatItem, f: Double, x: Double, curSec: Double) {
        val spb = 60.0 / b.bpm
        val l = b.loopLen()
        val pos = if (b.loop && l > 0) b.start + (((curSec - b.start) % l) + l) % l else curSec.coerceIn(0.0, b.duration)
        val m = (pos - b.start) / spb - 4
        Engine.nStartBeatAt(f.toLong(), pos, b.start, bpm / b.bpm, b.loop, l)
        shift = x - m
        gridSegs.add(GridSeg(x, shift, true))
        clickNext = ceil(m).toInt()
    }

    /** Cambia inicio o largo de la pista en Libre: se aplica directo, sin cortar. */
    fun editBeat(moveOne: Boolean, fn: (com.agenciapeluda.kutty.audio.BeatItem) -> Unit) {
        val b = Store.curBeat() ?: return
        if (running && libre && !beatPaused) {
            val f = Engine.nWritten() + 0.03 * sr; val x = beatAt(f)
            val cur = b.secAt(x - shift)
            fn(b)
            if (cur != null) restartFrom(b, f, x, cur)
        } else { fn(b); if (running && libre) beatEdited = true }
        Store.saveAudioMeta(b.key)
        if (moveOne) gridMoved = true
    }

    /** El 1 de la pista donde estás escuchando ahora. */
    fun markOne() {
        val b = Store.curBeat() ?: return
        if (!running || beatPaused) return
        val s = b.secAt(heardBeat - shift) ?: return
        editBeat(true) { it.start = s.coerceIn(0.0, max(0.0, it.duration - 0.5)) }
    }

    private fun finishLibre() {
        closeChain(System.currentTimeMillis())
        val dur = max(1, ((System.currentTimeMillis() - t0) / 1000).toInt())
        if (tricks.isEmpty() && cutT.isEmpty() && dur < 5) return
        val counts = tricks.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        val act = max(1, active.size)
        val cps = cutT.size.toDouble() / act
        var peak = 0; var j = 0
        for (i in cutT.indices) { while (cutT[i] - cutT[j] > 1.0) j++; peak = max(peak, i - j + 1) }
        val nCuts = cutT.size
        val clean = if (nCuts > 0) (100.0 * cutQ[0] / nCuts).roundToInt() else null
        val gs = tricks.mapNotNull { it.grid }
        val on = gs.count { it != "fuera" }
        val gridPct = if (gs.isEmpty()) null else (100.0 * on / gs.size).roundToInt()
        val n16 = gs.count { it == "1/16" }; val n3 = gs.count { it == "tresillo" }
        val gType = if (on == 0) null else if (n16 >= n3) "1/16" else "tresillo"
        val extra = org.json.JSONObject().apply {
            put("tricks", org.json.JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } })
            put("n", tricks.size); put("cuts", nCuts); put("cps", (cps * 10).roundToInt() / 10.0); put("peak", peak)
            clean?.let { put("clean", it) }; gridPct?.let { put("grid", it) }; gType?.let { put("gridType", it) }
        }
        val rec = SessionRec(System.currentTimeMillis(), "libre", "libre", bpm, -1, 0, dur, IntArray(4), bestCombo, false, extra.toString())
        Store.sessions.add(rec); Store.save()
        libreRead = LibreRead(rec, counts, tricks.size, cps, peak, nCuts, clean, gridPct, gType, bestCombo)
    }

    fun closeLibreRead() { libreRead = null }
}
