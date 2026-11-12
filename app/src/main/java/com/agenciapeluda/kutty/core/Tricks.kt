package com.agenciapeluda.kutty.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reconocimiento de trucos en el trazo del modo Libre: lee el disco y el fader
 * (lo mismo que mide la puntuación) y nombra lo que hiciste.
 * Puro: sin Android, con tests en app/src/test.
 */

/** Calidad de un corte: limpio, con rebote o sucio (el fader tembló). */
object CutQ { const val CLEAN: Byte = 0; const val BOUNCE: Byte = 1; const val DIRTY: Byte = 2 }

class Trick(
    val name: String, val fam: String,
    /** Clicks del fader: cortes en los trucos de fader abierto, aperturas en transformer y crab. */
    val clicks: Int,
    /** Segundos reales. */
    val t0: Double, val t1: Double,
    /** Eje del gráfico (tiempos del reloj de la sesión). */
    val x0: Double, val x1: Double,
    val pLo: Double, val pHi: Double,
    /** Cada corte (abierto → cerrado): tiempo, posición en el gráfico, disco y calidad. */
    val cutT: DoubleArray, val cutX: DoubleArray, val cutP: DoubleArray, val cutQ: ByteArray,
    /** "1/16", "tresillo", "fuera" o null si el beat estaba en pausa. */
    val grid: String?,
) {
    val cuts get() = cutQ.size
    val clean get() = cutQ.count { it == CutQ.CLEAN }
    override fun toString() = "$name [${"%.3f".format(t0)}–${"%.3f".format(t1)}]"
}

/** Trazo crudo: tiempo (s), eje del gráfico, tiempo musical (NaN en pausa), disco y fader. */
class Trace(cap: Int = 1 shl 14) {
    var t = DoubleArray(cap); var x = DoubleArray(cap); var m = DoubleArray(cap); var p = FloatArray(cap); var f = ByteArray(cap)
    var n = 0
        private set
    fun clear() { n = 0 }
    fun add(time: Double, xv: Double, mv: Double, pos: Float, fad: Byte) {
        if (n > 0 && time <= t[n - 1]) return
        if (n == t.size) {
            val k = n / 2
            System.arraycopy(t, k, t, 0, n - k); System.arraycopy(x, k, x, 0, n - k); System.arraycopy(m, k, m, 0, n - k)
            System.arraycopy(p, k, p, 0, n - k); System.arraycopy(f, k, f, 0, n - k)
            n -= k
        }
        t[n] = time; x[n] = xv; m[n] = mv; p[n] = pos; f[n] = fad; n++
    }
    /** Primer índice con t ≥ time. */
    fun idx(time: Double): Int {
        var lo = 0; var hi = n
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t[mid] < time) lo = mid + 1 else hi = mid }
        return lo
    }
}

object Tricks {
    /** Para los tests: cuenta qué vio en cada trazo. */
    var debug: ((String) -> Unit)? = null
    // disco
    private const val PIVOT = 0.035        // vuelta mínima para contar un cambio de dirección (unidades)
    private const val MIN_AMP = 0.06       // trazo mínimo
    private const val JUMP = 0.3           // salto de posición (un cue): corta el trazo
    private const val GAP = 0.04           // hueco de muestras (s)
    private const val PAIR_GAP = 0.25      // ida y vuelta separadas como mucho esto (s)
    // fader
    private const val BOUNCE_OPEN = 0.012  // un rebote: se abre menos de esto…
    private const val BOUNCE_AFTER = 0.02  // …apenas cerrado
    private const val GLITCH = 0.010       // tramos más cortos: temblor
    // grilla
    private const val GRID_TOL = 0.035     // s

    private class Seg(var a: Double, var b: Double, val st: Int, var bounces: Int = 0, var dirty: Boolean = false) { val d get() = b - a }

    private class Stroke(
        val i0: Int, val i1: Int, val dir: Int, val amp: Double,
        val a0: Int, val a1: Int,        // parte activa (sale del extremo / llega al otro)
        val ta0: Double, val ta1: Double, val pause: Boolean,
    ) {
        val dur get() = ta1 - ta0
        /** Ventana para leer el fader: del giro al giro; si arranca o termina quieto, un poco antes o después del movimiento. */
        var wa = ta0; var wb = ta1
    }

    /** Fader de un trazo: tramos y rasgos. */
    private class Word(val st: IntArray, val d: DoubleArray) {
        val total = d.sum()
        val openFrac = if (total <= 0) 0.0 else st.indices.sumOf { if (st[it] == 1) d[it] else 0.0 } / total
        val n get() = st.size
        val first get() = if (st.isEmpty()) -1 else st[0]
        val last get() = if (st.isEmpty()) -1 else st[st.size - 1]
        val open get() = openFrac > 0.88
        val closed get() = openFrac < 0.12
        /** Cierres con abierto a los dos lados (clicks de flare). */
        val innerCuts get() = (1 until n - 1).count { st[it] == 0 && st[it - 1] == 1 && st[it + 1] == 1 }
        /** Tramos abiertos (golpes de transformer y crab). */
        val opens get() = st.count { it == 1 }
    }

    /** Analiza el trazo entre los índices [from, to) y devuelve los trucos reconocidos, en orden. */
    fun analyze(tr: Trace, from: Int = 0, to: Int = tr.n): List<Trick> {
        if (to - from < 20) return emptyList()
        val segs = faderSegs(tr, from, to)
        val strokes = strokes(tr, from, to)
        val out = ArrayList<Trick>()
        var i = 0
        while (i < strokes.size) {
            // scribble: cuatro o más trazos mínimos y rapidísimos seguidos
            var j = i
            while (j < strokes.size && strokes[j].amp < 0.32 && strokes[j].dur < 0.14 && (j == i || strokes[j].ta0 - strokes[j - 1].ta1 < 0.03)) j++
            if (j - i >= 4) { out += make(tr, segs, "Scribble", "Base", -1, strokes[i], strokes[j - 1]); i = j; continue }
            val F = strokes[i]
            val B = strokes.getOrNull(i + 1)
            if (F.dir == 1 && B != null && B.dir == -1 && B.ta0 - F.ta1 < PAIR_GAP) {
                val k = classify(tr, segs, F, B)
                if (k != null) { out += k; i += 2; continue }
            }
            i++
        }
        return out
    }

    // ---------- fader ----------

    private fun faderSegs(tr: Trace, from: Int, to: Int): List<Seg> {
        val raw = ArrayList<Seg>()
        var st = tr.f[from].toInt(); var a = tr.t[from]
        for (k in from + 1 until to) {
            val s = tr.f[k].toInt()
            if (s != st) { raw += Seg(a, tr.t[k], st); a = tr.t[k]; st = s }
        }
        raw += Seg(a, tr.t[to - 1], st)
        // rebotes: abre un instante justo después de cerrar
        var k = 1
        while (k + 1 < raw.size) {
            val s = raw[k]
            if (s.st == 1 && s.d < BOUNCE_OPEN && raw[k - 1].st == 0 && raw[k - 1].d < BOUNCE_AFTER && raw[k + 1].st == 0 && k - 2 >= 0 && raw[k - 2].st == 1) {
                val c = raw[k - 1]; c.b = raw[k + 1].b; c.bounces += 1 + raw[k + 1].bounces; c.dirty = c.dirty || raw[k + 1].dirty
                raw.removeAt(k + 1); raw.removeAt(k)
                // el cierre quedó más largo: puede seguir otro rebote
                if (c.d < BOUNCE_AFTER) continue
            }
            k++
        }
        // temblores: tramos cortísimos se funden con lo de alrededor
        k = 1
        while (k + 1 < raw.size) {
            val s = raw[k]
            if (s.d < GLITCH && raw[k - 1].st == raw[k + 1].st) {
                val p = raw[k - 1]; val n = raw[k + 1]
                p.b = n.b; p.bounces += n.bounces
                // con el fader cerrado se coló sonido: ese corte salió sucio
                p.dirty = p.dirty || n.dirty || p.st == 0
                raw.removeAt(k + 1); raw.removeAt(k)
                continue
            }
            k++
        }
        return raw
    }

    private fun word(segs: List<Seg>, a: Double, b: Double): Word {
        val st = ArrayList<Int>(); val d = ArrayList<Double>()
        for (s in segs) {
            if (s.b <= a || s.a >= b) continue
            val x = max(a, s.a); val y = min(b, s.b)
            if (y <= x) continue
            if (st.isNotEmpty() && st.last() == s.st) d[d.size - 1] += y - x else { st += s.st; d += y - x }
        }
        // las puntas cortas son el corte del trazo vecino corrido unos ms: afuera
        val drop = 0.016
        while (st.size > 1 && d[0] < drop) { st.removeAt(0); d.removeAt(0) }
        while (st.size > 1 && d[d.size - 1] < drop) { st.removeAt(st.size - 1); d.removeAt(d.size - 1) }
        // y lo que quedó pegado se junta
        val s2 = ArrayList<Int>(); val d2 = ArrayList<Double>()
        for (k in st.indices) { if (s2.isNotEmpty() && s2.last() == st[k]) d2[d2.size - 1] += d[k] else { s2 += st[k]; d2 += d[k] } }
        return Word(s2.toIntArray(), d2.toDoubleArray())
    }

    // ---------- disco ----------

    private fun strokes(tr: Trace, from: Int, to: Int): List<Stroke> {
        val out = ArrayList<Stroke>()
        var r0 = from
        for (k in from + 1..to) {
            val cut = k == to || tr.t[k] - tr.t[k - 1] > GAP || abs(tr.p[k] - tr.p[k - 1]) > JUMP
            if (!cut) continue
            if (k - r0 > 10) run(tr, r0, k, out)
            r0 = k
        }
        return out
    }

    /** Trazos de un tramo continuo: zigzag con histéresis. */
    private fun run(tr: Trace, s: Int, e: Int, out: MutableList<Stroke>) {
        val piv = ArrayList<Int>(); piv += s
        var dir = 0; var ext = s
        for (i in s + 1 until e) {
            val p = tr.p[i]
            when (dir) {
                0 -> if (abs(p - tr.p[s]) > PIVOT) { dir = if (p > tr.p[s]) 1 else -1; ext = i }
                1 -> if (p >= tr.p[ext]) ext = i else if (tr.p[ext] - p > PIVOT) { piv += ext; dir = -1; ext = i }
                else -> if (p <= tr.p[ext]) ext = i else if (p - tr.p[ext] > PIVOT) { piv += ext; dir = 1; ext = i }
            }
        }
        if (dir != 0) piv += ext
        if (piv.last() != e - 1) piv += e - 1
        val first = out.size
        for (k in 0 until piv.size - 1) {
            val a = piv[k]; val b = piv[k + 1]
            val pa = tr.p[a].toDouble(); val pb = tr.p[b].toDouble()
            val amp = abs(pb - pa)
            if (amp < MIN_AMP) continue
            val eps = max(0.004, 0.004 * amp)   // quieto en el extremo (stab, arranque): afuera; el giro no
            var a0 = a; while (a0 < b && abs(tr.p[a0] - pa) <= eps) a0++
            var a1 = b; while (a1 > a0 && abs(pb - tr.p[a1]) <= eps) a1--
            a0 = max(a, a0 - 1); a1 = min(b, a1 + 1)
            if (a1 - a0 < 3) continue
            out += Stroke(a, b, if (pb > pa) 1 else -1, amp, a0, a1, tr.t[a0], tr.t[a1], pause(tr, a0, a1, amp))
        }
        for (k in first until out.size) {
            val s0 = out[k]
            val prev = if (k > first) out[k - 1] else null
            val next = if (k + 1 < out.size) out[k + 1] else null
            // giro sin quedarse quieto: el fader se lee hasta el giro mismo
            fun turn(x: Stroke, y: Stroke) = x.i1 == y.i0 && y.ta0 - x.ta1 < max(0.03, 0.12 * min(x.dur, y.dur))
            s0.wa = if (prev != null && turn(prev, s0)) tr.t[s0.i0] else s0.ta0
            s0.wb = if (next != null && turn(s0, next)) tr.t[s0.i1] else s0.ta1
        }
    }

    /** Una frenada en el medio del trazo, sin volver (tear). */
    private fun pause(tr: Trace, a0: Int, a1: Int, amp: Double): Boolean {
        val dur = tr.t[a1] - tr.t[a0]
        if (dur <= 0) return false
        val mean = amp / dur
        val lo = tr.t[a0] + 0.15 * dur; val hi = tr.t[a1] - 0.15 * dur
        val need = max(0.012, 0.05 * dur)
        var runStart = -1.0; var best = 0.0
        var j = a0
        while (j <= a1) {
            val tj = tr.t[j]
            if (tj < lo) { j++; continue }
            if (tj > hi) break
            // velocidad en ±3 ms
            var u = j; while (u < a1 && tr.t[u] < tj + 0.003) u++
            var w = j; while (w > a0 && tr.t[w] > tj - 0.003) w--
            val v = if (tr.t[u] > tr.t[w]) abs(tr.p[u] - tr.p[w]) / (tr.t[u] - tr.t[w]) else 0.0
            if (v < 0.12 * mean) { if (runStart < 0) runStart = tj; best = max(best, tj - runStart) } else runStart = -1.0
            j++
        }
        return best >= need
    }

    // ---------- trucos ----------

    private fun classify(tr: Trace, segs: List<Seg>, F: Stroke, B: Stroke): Trick? {
        val a = word(segs, F.wa, F.wb); val b = word(segs, B.wa, B.wb)
        debug?.invoke("F %.3f-%.3f p%s %s · B %.3f-%.3f p%s %s".format(F.ta0, F.ta1, F.pause, w(a), B.ta0, B.ta1, B.pause, w(b)))
        if (a.n == 0 || b.n == 0) return null
        return when {
            (F.pause || B.pause) && a.openFrac > 0.75 && b.openFrac > 0.75 -> make(tr, segs, "Tear", "Tears", -1, F, B)
            a.open && b.open -> make(tr, segs, "Baby", "Base", -1, F, B)
            a.open && b.closed -> if (F.dur < 0.72 * B.dur) make(tr, segs, "Stab", "Cortes", -1, F, B) else make(tr, segs, "Forward", "Cortes", -1, F, B)
            a.closed && b.open -> make(tr, segs, "Backward", "Cortes", -1, F, B)
            a.n == 2 && a.first == 1 && a.last == 0 && b.n == 2 && b.first == 0 && b.last == 1 -> make(tr, segs, "Chirp", "Chirps", -1, F, B)
            a.openFrac > 0.5 && b.openFrac > 0.5 && a.first == 1 && a.last == 1 && b.first == 1 && b.last == 1 && (a.innerCuts > 0 || b.innerCuts > 0) -> {
                val ka = a.innerCuts; val kb = b.innerCuts
                if (ka > 0 && kb > 0) {
                    val k = max(ka, kb)
                    make(tr, segs, "Orbit $k click" + (if (k > 1) "s" else ""), "Flares", ka + kb, F, B)
                } else {
                    val k = ka + kb
                    make(tr, segs, "Flare $k click" + (if (k > 1) "s" else ""), "Flares", k, F, B)
                }
            }
            a.openFrac < 0.65 && a.opens >= 2 && (a.first == 0 || a.n >= 4) -> {
                val taps = a.opens + (if (b.openFrac < 0.65 && b.first == 0) b.opens else 0)
                if (a.last == 1) make(tr, segs, "Transformer", "Transformers", taps, F, B)
                else {
                    val k = a.opens
                    make(tr, segs, if (k == 2) "Twiddle" else "Crab $k dedos", "Crabs", taps, F, B)
                }
            }
            else -> null
        }
    }

    private fun w(x: Word) = x.st.indices.joinToString("") { "${x.st[it]}(${(x.d[it] * 1000).toInt()})" } + " o%.2f".format(x.openFrac)

    private fun make(tr: Trace, segs: List<Seg>, name: String, fam: String, clicks: Int, F: Stroke, B: Stroke): Trick {
        val t0 = F.ta0; val t1 = B.ta1
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (k in F.a0..B.a1) { val p = tr.p[k].toDouble(); if (p < lo) lo = p; if (p > hi) hi = p }
        // cortes del truco: cierres que empiezan adentro (un poco de margen por el corte en el giro)
        val cT = ArrayList<Double>(); val cQ = ArrayList<Byte>()
        for ((k, s) in segs.withIndex()) {
            if (s.st != 0 || k == 0 || segs[k - 1].st != 1) continue
            if (s.a < t0 - 0.01 || s.a > t1 + 0.01) continue
            cT += s.a; cQ += if (s.dirty || s.bounces >= 2) CutQ.DIRTY else if (s.bounces == 1) CutQ.BOUNCE else CutQ.CLEAN
        }
        val i0 = tr.idx(t0)
        val cX = DoubleArray(cT.size); val cP = DoubleArray(cT.size)
        for (k in cT.indices) { val j = tr.idx(cT[k]).coerceIn(0, tr.n - 1); cX[k] = tr.x[j]; cP[k] = tr.p[j].toDouble() }
        return Trick(name, fam, if (clicks >= 0) clicks else cT.size, t0, t1, tr.x[i0.coerceIn(0, tr.n - 1)], tr.x[tr.idx(t1).coerceIn(0, tr.n - 1)], lo, hi,
            cT.toDoubleArray(), cX, cP, cQ.toByteArray(), grid(tr, i0))
    }

    /** ¿El truco arrancó en la grilla? Semicorcheas o tresillos, con ±35 ms. */
    private fun grid(tr: Trace, i: Int): String? {
        if (i < 0 || i >= tr.n) return null
        val m = tr.m[i]
        if (m.isNaN()) return null
        // tempo local: tiempos musicales por segundo
        val j0 = tr.idx(tr.t[i] - 0.2).coerceIn(0, tr.n - 1); val j1 = (tr.idx(tr.t[i] + 0.2)).coerceIn(0, tr.n - 1)
        val dt = tr.t[j1] - tr.t[j0]
        val rate = if (dt > 0.05 && !tr.m[j0].isNaN() && !tr.m[j1].isNaN()) (tr.m[j1] - tr.m[j0]) / dt else return null
        if (rate <= 0) return null
        val tol = GRID_TOL * rate
        fun dist(q: Double) = abs(m / q - (m / q).roundToInt()) * q
        val d16 = dist(0.25); val d3 = dist(1.0 / 3)
        return when {
            min(d16, d3) > tol -> "fuera"
            d16 <= d3 -> "1/16"
            else -> "tresillo"
        }
    }

}

/** Reconocimiento en vivo: analiza los últimos segundos y entrega cada truco una sola vez, ya cerrado. */
class TrickTracker {
    val trace = Trace()
    private var lastT1 = -1e9
    fun reset() { trace.clear(); lastT1 = -1e9 }
    fun add(time: Double, x: Double, m: Double, p: Float, f: Byte) = trace.add(time, x, m, p, f)

    fun poll(): List<Trick> {
        val tr = trace
        if (tr.n < 40) return emptyList()
        val now = tr.t[tr.n - 1]
        val i0 = tr.idx(now - WINDOW)
        val winStart = tr.t[i0]
        val list = Tricks.analyze(tr, i0, tr.n)
        val out = list.filter { it.t1 < now - SETTLE && it.t0 > lastT1 - 0.005 && (i0 == 0 || it.t0 > winStart + 0.4) }
        if (out.isNotEmpty()) lastT1 = out.last().t1
        return out
    }

    companion object { const val WINDOW = 8.0; const val SETTLE = 0.12 }
}

/**
 * Cortes en vivo, apenas pasan: limpio, con rebote o sucio (más de un rebote, o un cierre
 * de menos de 10 ms). Para las estrellas y la lectura.
 */
class CutWatch(private val onCut: (t: Double, x: Double, p: Double, q: Byte) -> Unit) {
    private var last = -1
    private var pending = false
    private var cT = 0.0; private var cX = 0.0; private var cP = 0.0
    private var reopen = -1.0
    private var bounces = 0

    fun reset() { last = -1; pending = false }

    fun feed(t: Double, x: Double, p: Double, f: Int) {
        if (pending) {
            if (reopen >= 0 && t - reopen >= 0.012) {
                // se abrió de verdad: si estuvo cerrado menos de 10 ms, fue un temblor
                emit(if (reopen - cT < 0.010) CutQ.DIRTY else q())
            } else if (reopen < 0 && t - cT > 0.03) emit(q())
        }
        if (last == 1 && f == 0) {
            if (pending && reopen >= 0) { bounces++; reopen = -1.0 }
            else if (!pending) { pending = true; cT = t; cX = x; cP = p; reopen = -1.0; bounces = 0 }
        } else if (last == 0 && f == 1 && pending) reopen = t
        last = f
    }

    private fun q(): Byte = if (bounces >= 2) CutQ.DIRTY else if (bounces == 1) CutQ.BOUNCE else CutQ.CLEAN
    private fun emit(q: Byte) { pending = false; onCut(cT, cX, cP, q) }
}
