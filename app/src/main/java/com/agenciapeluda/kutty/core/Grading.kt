package com.agenciapeluda.kutty.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/** Calificación de un trazo, con lo que falló para la devolución. */
class Grade(
    val score: Double, val dir: Double, val amp: Double, val fad: Double, val open: Double?, val closed: Double?,
    val lag: Double, val ratio: Double?, val final: Double, val grade: Int,
    /** 1 ida, -1 vuelta, 0 quieto. */
    val way: Int = 0,
    /** Error del cambio de dirección (tiempos, + tarde), ya descontado el desfase general. */
    val turnErr: Double? = null,
    /** Errores de cada corte/apertura encontrados (tiempos, + tarde) y su tipo (0 corte, 1 apertura). */
    val edgeErr: DoubleArray = DoubleArray(0), val edgeTo: ByteArray = ByteArray(0),
    val edgeN: Int = 0, val edgeMiss: Int = 0,
) {
    companion object { const val PERFECT = 0; const val GOOD = 1; const val WEAK = 2; const val MISS = 3 }
}

/** Muestras del usuario ordenadas por tiempo (en tiempos de compás). */
class Samples(cap: Int = 1 shl 16) {
    var b = DoubleArray(cap); var p = FloatArray(cap); var f = ByteArray(cap)
    var n = 0
        private set
    fun clear() { n = 0 }
    fun add(beat: Double, pos: Float, fader: Byte) {
        if (n == b.size) {
            // conserva la mitad más nueva
            val k = n / 2
            System.arraycopy(b, k, b, 0, n - k); System.arraycopy(p, k, p, 0, n - k); System.arraycopy(f, k, f, 0, n - k)
            n -= k
        }
        if (n > 0 && beat < b[n - 1]) {
            var i = n
            while (i > 0 && b[i - 1] > beat) i--
            System.arraycopy(b, i, b, i + 1, n - i); System.arraycopy(p, i, p, i + 1, n - i); System.arraycopy(f, i, f, i + 1, n - i)
            b[i] = beat; p[i] = pos; f[i] = fader; n++
            return
        }
        b[n] = beat; p[n] = pos; f[n] = fader; n++
    }
    fun idxAt(x: Double): Int {
        if (n == 0 || x < b[0]) return -1
        var lo = 0; var hi = n - 1
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (b[m] <= x) lo = m else hi = m - 1 }
        return lo
    }
    fun pAt(x: Double): Double {
        val i = idxAt(x)
        if (i < 0) return if (n > 0) p[0].toDouble() else 0.0
        if (i + 1 >= n || b[i + 1] == b[i]) return p[i].toDouble()
        return p[i] + (p[i + 1] - p[i]) * (x - b[i]) / (b[i + 1] - b[i])
    }
    fun fAt(x: Double): Int { val i = idxAt(x); return if (i < 0) 0 else f[i].toInt() }
}


/** Desfase general: la mediana de lo que viene midiendo cada trazo. Un adelanto o atraso parejo no se cobra como error. */
class LagTracker {
    private val hist = ArrayList<Double>()
    fun reset() = hist.clear()
    fun value(): Double {
        if (hist.isEmpty()) return 0.0
        val s = hist.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
    fun with(x: Double?): Double { if (x != null) { hist.add(x); if (hist.size > 8) hist.removeAt(0) }; return value() }
}

object Grading {
    /** La curva de referencia en un instante (la misma para gráfico, demo, manos automáticas y puntuación). */
    fun refAt(c: Curve, b: Double): Ref = c.at(b)

    private class Turn(val t: Double, val p: Double)

    /** Giro dentro de una ventana: el centro del tramo que queda cerca del extremo. Null si no hay giro. */
    private fun turn(f: (Double) -> Double, c: Double, hw: Double, peak: Int, thr: Double, h: Double): Turn? {
        val m = max(6, ceil(2 * hw / h).toInt()); val step = 2 * hw / m
        val vals = DoubleArray(m + 1) { peak * f(c - hw + it * step) }
        var bi = 0
        for (k in 1..m) if (vals[k] > vals[bi]) bi = k
        if (bi == 0 || bi == m) return null
        val bv = vals[bi]
        var lo = bi; while (lo > 0 && vals[lo - 1] >= bv - thr) lo--
        var hi = bi; while (hi < m && vals[hi + 1] >= bv - thr) hi++
        return Turn(c - hw + (lo + hi) / 2.0 * step, peak * bv)
    }

    private fun timeScore(e: Double, tol: Double) = if (e <= tol) 1.0 else max(0.0, 1 - (e - tol) / (1.5 * tol))

    private fun travelScore(r: Double, free: Double): Double {
        if (r <= 0 || !r.isFinite()) return 0.0
        val e = when { r < 1 - free -> ln((1 - free) / r); r > 1 + free -> ln(r / (1 + free)); else -> 0.0 }
        return max(0.0, 1 - e / ln(1.6))
    }

    /**
     * Califica el trazo i contra la curva humana por sus puntos clave: dirección, recorrido,
     * momento de cada cambio de dirección y de cada corte/apertura. No mira la velocidad instantánea.
     */
    fun grade(timed: List<Timed>, i: Int, ref: Curve, sm: Samples, tol: Tol, part: String, lag: LagTracker): Grade {
        val s = timed[i]; val d = s.dur
        val way = Human.dirOf(s); val moving = way != 0
        val amp = abs(s.p1 - s.p0)
        val prev = if (Human.contiguous(timed, i)) timed[i - 1] else null
        val next = if (i + 1 < timed.size && Human.contiguous(timed, i + 1)) timed[i + 1] else null
        val revS = Human.reversal(timed, i); val revE = i + 1 < timed.size && Human.reversal(timed, i + 1)
        val h = 0.002
        val L0 = lag.value()
        val Lmax = 2 * tol.lagB
        val user = { b: Double -> sm.pAt(b) }
        val rf = { b: Double -> ref.pos(b) }
        fun hw(adj: Timed) = min(0.45 * min(d, adj.dur), 2.5 * tol.turnB)

        var rS: Turn? = null; var uS: Turn? = null; var rE: Turn? = null; var uE: Turn? = null
        if (revS) {
            val w = hw(prev!!); val thr = 0.02 * max(amp, abs(prev.p1 - prev.p0))
            rS = turn(rf, s.b0, w, -way, thr, h); uS = turn(user, s.b0 + L0, w, -way, thr, h)
        }
        if (revE) {
            val w = hw(next!!); val thr = 0.02 * max(amp, abs(next.p1 - next.p0))
            rE = turn(rf, s.b1, w, way, thr, h); uE = turn(user, s.b1 + L0, w, way, thr, h)
        }

        // fader: cortes y aperturas del trazo, tal cual la máscara
        val edges = ArrayList<Pair<Double, Int>>()
        val n = s.mask.length
        if (prev != null && prev.mask.last() != s.mask[0]) edges.add(s.b0 to (s.mask[0] - '0'))
        for (k in 1 until n) if (s.mask[k] != s.mask[k - 1]) edges.add(s.b0 + d * k / n to (s.mask[k] - '0'))
        val win = 2.5 * tol.cutB
        val ut = ArrayList<Double>(); val us = ArrayList<Int>()
        run {
            val lo = s.b0 - Lmax - win - 0.05; val hi = s.b1 + Lmax + win
            var j = max(1, sm.idxAt(lo))
            while (j < sm.n && sm.b[j] <= hi) { if (sm.f[j] != sm.f[j - 1]) { ut.add(sm.b[j]); us.add(sm.f[j].toInt()) }; j++ }
        }
        fun match(t: Double, to: Int): Double? {
            var best: Double? = null
            for (k in ut.indices) { if (us[k] != to) continue; val o = ut[k] - t; if (abs(o) <= win && (best == null || abs(o) < abs(best))) best = o }
            return best
        }

        // desfase general: lo mide la mano que toca el usuario
        val est: Double? = if (part == "fader") {
            val offs = edges.mapNotNull { e -> match(e.first + L0, e.second)?.let { it + L0 } }
            if (offs.isEmpty()) null else offs.average()
        } else {
            val m = listOfNotNull(if (rS != null && uS != null) uS.t - rS.t else null, if (rE != null && uE != null) uE.t - rE.t else null)
            if (m.isEmpty()) null else m.average()
        }
        val L = lag.with(est?.coerceIn(-Lmax, Lmax)).coerceIn(-Lmax, Lmax)

        // cambios de dirección
        val turns = ArrayList<Double>()
        var turnErr: Double? = null
        if (rS != null) turns.add(if (uS == null) 0.0 else timeScore(abs(uS.t - rS.t - L), tol.turnB))
        if (rE != null) {
            turns.add(if (uE == null) 0.0 else timeScore(abs(uE.t - rE.t - L), tol.turnB))
            if (uE != null) turnErr = uE.t - rE.t - L
        }

        // dirección y recorrido
        val c = min(0.2 * d, tol.turnB)
        var dir: Double
        var travel = 1.0; var ratio: Double? = null
        if (moving) {
            val M = 8; val span = d - 2 * c; var ok = 0
            for (m in 0 until M) {
                val ta = s.b0 + c + span * m / M; val tb = ta + span / M
                val du = user(tb + L) - user(ta + L); val dr = rf(tb) - rf(ta)
                if (abs(dr) < 1e-3 * amp) { if (du * way > -0.02 * amp) ok++ } else if (du * way >= 0.1 * abs(dr)) ok++
            }
            dir = ok.toDouble() / M
            val r0 = rS?.p ?: rf(s.b0); val r1 = rE?.p ?: rf(s.b1)
            val u0 = if (revS) uS?.p ?: user(s.b0 + L) else user(s.b0 + L)
            val u1 = if (revE) uE?.p ?: user(s.b1 + L) else user(s.b1 + L)
            val rr = (u1 - u0) / (r1 - r0); ratio = rr
            travel = travelScore(rr, tol.travel)
        } else {
            val a = s.b0 + c + L; val span = max(0.0, d - 2 * c); val base = user(a)
            var dev = 0.0
            for (m in 0..12) dev = max(dev, abs(user(a + span * m / 12) - base))
            dir = (1 - max(0.0, dev - 0.1) / 0.2).coerceIn(0.0, 1.0)
        }
        val motion = if (moving) (if (turns.isEmpty()) dir else 0.55 * dir + 0.45 * turns.average()) * travel else dir

        // fader: momento de cada corte y estado en el medio de cada tramo
        val eErr = ArrayList<Double>(); val eTo = ArrayList<Byte>(); var miss = 0; var eSum = 0.0
        for ((t, to) in edges) {
            val o = match(t + L, to)
            if (o == null) miss++ else { eErr.add(o); eTo.add(to.toByte()); eSum += timeScore(abs(o), tol.cutB) }
        }
        val near = ArrayList<Double>(edges.map { it.first })
        if (next != null && next.mask[0] != s.mask.last()) near.add(s.b1)
        var oOk = 0; var oN = 0; var cOk = 0; var cN = 0
        val N = 48; val ex = min(tol.cutB, 0.3 * d / n)
        for (k in 0 until N) {
            val x = (k + 0.5) / N; val b = s.b0 + x * d
            if (near.any { abs(it - b) < ex }) continue
            val want = s.mask[min(n - 1, floor(x * n).toInt())] - '0'
            val ok = sm.fAt(b + L) == want
            if (want == 1) { oN++; if (ok) oOk++ } else { cN++; if (ok) cOk++ }
        }
        val o = if (oN > 0) oOk.toDouble() / oN else null
        val cl = if (cN > 0) cOk.toDouble() / cN else null
        val state = when {
            o == null && cl == null -> 1.0
            o == null -> cl!!
            cl == null -> o
            o + cl == 0.0 -> 0.0
            else -> 2 * o * cl / (o + cl)
        }
        val fad = if (edges.isEmpty()) state else 0.5 * (eSum / edges.size) + 0.5 * state

        val score = when (part) { "plato" -> motion; "fader" -> fad; else -> 0.45 * motion + 0.55 * fad }
        val final = score * (1 - 0.3 * max(0.0, abs(L) - tol.lagB) / tol.lagB).coerceAtLeast(0.4)
        val g = when { final >= 0.85 -> Grade.PERFECT; final >= 0.65 -> Grade.GOOD; final >= 0.4 -> Grade.WEAK; else -> Grade.MISS }
        return Grade(score, dir, travel, fad, o, cl, L, ratio, final, g, way, turnErr, eErr.toDoubleArray(), eTo.toByteArray(), edges.size, miss)
    }

    private fun median(l: List<Double>): Double { val s = l.sorted(); val m = s.size / 2; return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2 }

    /** Devolución en palabras: (nivel: 0 ok, 1 aviso, 2 mal, texto). Nombra qué falló. */
    fun feedback(graded: List<Timed>, bpm: Double, acc: Double, learning: Boolean, part: String, tol: Tol): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val ms = { b: Double -> (b * 60000 / bpm).roundToInt() }
        val gs = graded.mapNotNull { it.g }
        if (gs.isEmpty()) return out
        val pct = { x: Double -> (abs(x) * 100).roundToInt() }
        if (part != "plato") {
            val n = gs.sumOf { it.edgeN }; val miss = gs.sumOf { it.edgeMiss }
            if (n > 0 && miss.toDouble() / n > 0.2) out.add(2 to "Te faltaron $miss de $n movimientos del fader (cortes y aperturas).")
            var timingIssue = false
            val all = ArrayList<Double>()
            for (to in 0..1) {
                val errs = gs.flatMap { g -> g.edgeErr.indices.filter { g.edgeTo[it].toInt() == to }.map { g.edgeErr[it] } }
                all.addAll(errs)
                if (errs.size < 3) continue
                val m = ms(median(errs))
                if (abs(m) >= max(12, tol.cutMs / 2)) {
                    timingIssue = true
                    val lv = if (abs(m) > 1.6 * tol.cutMs) 2 else 1
                    val cuando = if (m > 0) "tarde" else "antes"
                    out.add(lv to if (to == 0) "El corte entró ${abs(m)} ms $cuando." else "La apertura del fader llegó ${abs(m)} ms $cuando.")
                }
            }
            if (!timingIssue && all.size >= 3) {
                val m = all.average(); val sd = sqrt(all.sumOf { (it - m) * (it - m) } / all.size)
                out.add(0 to "Cortes a tiempo (desvío ±${ms(sd)} ms).")
            }
            val closed = gs.mapNotNull { it.closed }; val open = gs.mapNotNull { it.open }
            if (closed.isNotEmpty() && closed.average() < 0.7) out.add(2 to "Dejás el fader abierto donde tiene que estar cerrado.")
            if (open.isNotEmpty() && open.average() < 0.7) out.add(2 to "Te falta abrir el fader en las partes que suenan.")
        }
        if (part != "fader") {
            val mv = gs.filter { it.way != 0 }
            val dirBad = if (mv.isEmpty()) 0.0 else mv.count { it.dir < 0.5 }.toDouble() / mv.size
            if (dirBad > 0.15) out.add(2 to "En el ${(dirBad * 100).roundToInt()}% de los trazos el disco fue para otro lado o se quedó quieto.")
            for (w in listOf(1, -1)) {
                val name = if (w == 1) "la ida" else "la vuelta"
                val rs = gs.filter { it.way == w }.mapNotNull { it.ratio }.filter { it.isFinite() }
                if (rs.size < 2) continue
                val m = median(rs)
                if (m < 1 - tol.travel) out.add((if (m < 0.6) 2 else 1) to "Te faltó recorrido en $name: movés el disco un ${pct(1 - m)}% menos de lo que pide la técnica.")
                else if (m > 1 + tol.travel) out.add(1 to "Te pasaste de recorrido en $name: un ${pct(m - 1)}% de más.")
                val te = gs.filter { it.way == w }.mapNotNull { it.turnErr }
                if (te.size >= 3) {
                    val t = ms(median(te))
                    if (abs(t) >= max(15, tol.turnMs / 2)) out.add((if (abs(t) > 1.6 * tol.turnMs) 2 else 1) to "El cambio de dirección al final de $name llega ${abs(t)} ms ${if (t > 0) "tarde" else "antes"}.")
                }
            }
        }
        val lag = ms(gs.map { it.lag }.average())
        if (abs(lag) > tol.lagMs) out.add(1 to "En general vas ${abs(lag)} ms ${if (lag > 0) "atrasado" else "adelantado"} respecto del beat; hasta ${tol.lagMs} ms no te descuenta.")
        else if (abs(lag) > 20) out.add(0 to "Vas ${abs(lag)} ms ${if (lag > 0) "atrasado" else "adelantado"} parejo: dentro del margen, no te descuenta.")
        val sorted = out.sortedByDescending { it.first }.toMutableList()
        if (acc >= 0.85) sorted.add(0 to when {
            learning -> "Listo para Practicar."
            part == "plato" -> "La mano del disco está. Probá Solo fader y después Completo."
            part == "fader" -> "La mano del fader está. Probá Solo plato y después Completo."
            else -> "Técnica dominada. Probá subir el BPM o pasar a Desafío."
        })
        return if (sorted.size <= 5) sorted else if (acc >= 0.85) sorted.take(4) + sorted.last() else sorted.take(5)
    }
}
