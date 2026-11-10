package com.agenciapeluda.kutty.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

data class Grade(
    val score: Double, val dir: Double, val amp: Double, val fad: Double, val open: Double?, val closed: Double?,
    val lag: Double, val ratio: Double?, val final: Double, val grade: Int,
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

data class Ref(val p: Double, val f: Int, val v: Double)

object Grading {
    fun refAt(timed: List<Timed>, b: Double): Ref {
        if (timed.isEmpty()) return Ref(0.0, 0, 0.0)
        if (b <= timed[0].b0) return Ref(timed[0].p0, 0, 0.0)
        var lo = 0; var hi = timed.size - 1
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (timed[m].b0 <= b) lo = m else hi = m - 1 }
        val s = timed[lo]
        if (b >= s.b1) return Ref(s.p1, 0, 0.0)
        val x = (b - s.b0) / s.dur; val n = s.mask.length
        return Ref(s.p0 + (s.p1 - s.p0) * x, s.mask[min(n - 1, floor(x * n).toInt())] - '0', (s.p1 - s.p0) / s.dur)
    }

    private fun scoreAtLag(s: Timed, sm: Samples, L: Double, edgeB: Double): Grade {
        val N = 24; val d = s.dur; val exp = (s.p1 - s.p0) / d; val moving = abs(s.p1 - s.p0) > 1e-3
        val h = max(d / 40, 0.004)
        var dirOk = 0; var oOk = 0; var oN = 0; var cOk = 0; var cN = 0
        val n = s.mask.length
        for (i in 0 until N) {
            val x = (i + 0.5) / N; val b = s.b0 + x * d
            val v = (sm.pAt(b + L + h) - sm.pAt(b + L - h)) / (2 * h)
            if (moving) { if (sign(v) == sign(exp) && abs(v) > max(0.15 * abs(exp), 0.08)) dirOk++ } else if (abs(v) < 0.35) dirOk++
            val k = x * n; val rk = Math.round(k).toInt()
            val near = abs(k - rk) * (d / n) < edgeB && rk > 0 && rk < n
            if (!near) {
                val want = s.mask[min(n - 1, floor(k).toInt())] - '0'
                val ok = sm.fAt(b + L) == want
                if (want == 1) { oN++; if (ok) oOk++ } else { cN++; if (ok) cOk++ }
            }
        }
        val dir = dirOk.toDouble() / N
        var amp = 1.0; var ratio: Double? = null
        if (moving) {
            val disp = sm.pAt(s.b1 + L) - sm.pAt(s.b0 + L)
            val r = disp / (s.p1 - s.p0); ratio = r
            amp = if (r <= 0) 0.0 else max(0.0, 1 - abs(ln(r) / ln(2.0)) / 1.6)
        }
        val motion = if (moving) 0.7 * dir + 0.3 * amp else dir
        val o = if (oN > 0) oOk.toDouble() / oN else null
        val c = if (cN > 0) cOk.toDouble() / cN else null
        val fad = when {
            o == null && c == null -> 1.0
            o == null -> c!!
            c == null -> o
            o + c == 0.0 -> 0.0
            else -> 2 * o * c / (o + c)
        }
        val score = 0.45 * motion + 0.55 * fad
        return Grade(score, dir, amp, fad, o, c, L, ratio, score, 0)
    }

    fun grade(s: Timed, sm: Samples, tolB: Double, edgeB: Double): Grade {
        var best: Grade? = null
        for (i in -10..10) {
            val L = tolB * i / 10
            val r = scoreAtLag(s, sm, L, edgeB)
            if (best == null || r.score > best.score + 1e-9 || (abs(r.score - best.score) < 1e-9 && abs(L) < abs(best.lag))) best = r
        }
        val b = best!!
        val final = b.score * (1 - 0.25 * abs(b.lag) / max(tolB, 1e-6))
        val g = when { final >= 0.85 -> Grade.PERFECT; final >= 0.65 -> Grade.GOOD; final >= 0.4 -> Grade.WEAK; else -> Grade.MISS }
        return b.copy(final = final, grade = g)
    }

    class ClickReport(val expected: Int, val miss: Int, val offsets: List<Double>)

    fun clickReport(timed: List<Timed>, sm: Samples, winB: Double): ClickReport {
        val exp = ArrayList<Pair<Double, Int>>()
        for (s in timed) {
            if (s.demo) continue
            val n = s.mask.length
            for (k in 1 until n) if (s.mask[k] != s.mask[k - 1]) exp.add(s.b0 + s.dur * k / n to (s.mask[k] - '0'))
        }
        val got = ArrayList<Pair<Double, Int>>()
        for (i in 1 until sm.n) if (sm.f[i] != sm.f[i - 1]) got.add(sm.b[i] to sm.f[i].toInt())
        val offs = ArrayList<Double>(); var miss = 0
        for ((eb, to) in exp) {
            var bestD = Double.MAX_VALUE
            for ((gb, gt) in got) { if (gt != to) continue; val dd = gb - eb; if (abs(dd) < abs(bestD)) bestD = dd }
            if (abs(bestD) <= winB) offs.add(bestD) else miss++
        }
        return ClickReport(exp.size, miss, offs)
    }

    /** Devolución en palabras: (nivel: 0 ok, 1 aviso, 2 mal, texto). */
    fun feedback(graded: List<Timed>, sm: Samples, bpm: Double, acc: Double, learning: Boolean): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val ms = { b: Double -> (b * 60000 / bpm).roundToInt() }
        val cr = clickReport(graded, sm, 0.09 * bpm / 60)
        if (cr.expected > 0) {
            if (cr.miss.toDouble() / cr.expected > 0.2) out.add(2 to "Te faltaron ${cr.miss} de ${cr.expected} cambios de fader.")
            if (cr.offsets.isNotEmpty()) {
                val m = cr.offsets.average()
                val sd = sqrt(cr.offsets.sumOf { (it - m) * (it - m) } / cr.offsets.size)
                if (abs(ms(m)) > 15) out.add(1 to "Tus clicks llegan ${abs(ms(m))} ms ${if (m > 0) "tarde" else "antes"}.")
                else out.add(0 to "Clicks a tiempo (desvío ±${ms(sd)} ms).")
            }
        }
        val gs = graded.mapNotNull { it.g }
        if (gs.isEmpty()) return out
        val dirBad = gs.count { it.dir < 0.5 }.toDouble() / gs.size
        if (dirBad > 0.15) out.add(2 to "En el ${(dirBad * 100).roundToInt()}% de los trazos el disco fue para otro lado o se quedó quieto.")
        val ratios = gs.mapNotNull { it.ratio }.filter { it > 0 && it.isFinite() }.sorted()
        if (ratios.isNotEmpty()) {
            val med = ratios[ratios.size / 2]
            if (med < 0.7) out.add(1 to "Trazos cortos: movés el disco un ${((1 - med) * 100).roundToInt()}% menos de lo que pide la técnica.")
            else if (med > 1.45) out.add(1 to "Trazos largos: movés el disco un ${((med - 1) * 100).roundToInt()}% de más.")
        }
        val closed = gs.mapNotNull { it.closed }; val open = gs.mapNotNull { it.open }
        if (closed.isNotEmpty() && closed.average() < 0.7) out.add(2 to "Dejás el fader abierto donde tiene que estar cerrado.")
        if (open.isNotEmpty() && open.average() < 0.7) out.add(2 to "Te falta abrir el fader en las partes que suenan.")
        val lag = gs.map { it.lag }.average()
        if (abs(ms(lag)) > 20) out.add(1 to "En general vas ${abs(ms(lag))} ms ${if (lag > 0) "atrasado" else "adelantado"} respecto del beat.")
        if (acc >= 0.85) out.add(0 to if (learning) "Listo para Practicar." else "Técnica dominada. Probá subir el BPM o pasar a Desafío.")
        return out.take(5)
    }
}
