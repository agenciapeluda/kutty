package com.agenciapeluda.kutty.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Tolerancias de la exigencia, en tiempos de compás (se arman con el BPM). */
class Tol(val turnB: Double, val cutB: Double, val lagB: Double, val travel: Double, val turnMs: Int, val cutMs: Int, val lagMs: Int) {
    companion object {
        /** giro, corte, desfase libre (ms) y recorrido libre (fracción). */
        val TABLE = mapOf(
            "facil" to (intArrayOf(70, 45, 90) to 0.35),
            "normal" to (intArrayOf(45, 30, 60) to 0.25),
            "pro" to (intArrayOf(30, 20, 40) to 0.15),
        )
        fun of(diff: String, bpm: Double): Tol {
            val (ms, tr) = TABLE[diff] ?: TABLE.getValue("normal")
            val k = bpm / 60000.0
            return Tol(ms[0] * k, ms[1] * k, ms[2] * k, tr, ms[0], ms[1], ms[2])
        }
    }
}

/** Lo que devuelve la curva en un instante: posición, velocidad (por tiempo) y fader. */
data class Ref(val p: Double, val f: Int, val v: Double)

/**
 * Curva de un plan de trazos: posición y velocidad del disco más el fader, en tiempos de compás.
 * La misma curva alimenta el gráfico, la demo, las manos automáticas y la puntuación.
 */
class Curve internal constructor(
    private val segs: Array<CSeg>, private val edgeT: DoubleArray, private val edgeS: ByteArray, private val f0: Int,
) {
    internal class CSeg(
        val t0: Double, val t1: Double, val q0: Double, val q1: Double,
        val v0: Double, val a0: Double, val v1: Double, val a1: Double, val k: Double, val lin: Boolean,
    )

    val start get() = if (segs.isEmpty()) 0.0 else segs[0].t0
    val end get() = if (segs.isEmpty()) 0.0 else segs[segs.size - 1].t1

    private fun segAt(b: Double): Int {
        var lo = 0; var hi = segs.size - 1
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (segs[m].t0 <= b) lo = m else hi = m - 1 }
        return lo
    }

    /** Posición (p) y velocidad (v, en posición por tiempo) del disco. */
    fun pos(b: Double): Double = eval(b, false)
    fun vel(b: Double): Double = eval(b, true)

    private fun eval(b: Double, wantV: Boolean): Double {
        if (segs.isEmpty()) return 0.0
        if (b <= segs[0].t0) return if (wantV) 0.0 else segs[0].q0
        val s = segs[segAt(b)]
        if (b >= s.t1) return if (wantV) 0.0 else s.q1
        val T = s.t1 - s.t0
        val x = (b - s.t0) / T
        val u = x + s.k * x * (1 - x)
        val du = 1 + s.k * (1 - 2 * x)
        val d = s.q1 - s.q0
        if (s.lin) return if (wantV) d / T else s.q0 + d * u
        val u2 = u * u; val u3 = u2 * u; val u4 = u3 * u; val u5 = u4 * u
        return if (!wantV) {
            s.q0 * (1 - 10 * u3 + 15 * u4 - 6 * u5) + s.v0 * (u - 6 * u3 + 8 * u4 - 3 * u5) + s.a0 * (0.5 * u2 - 1.5 * u3 + 1.5 * u4 - 0.5 * u5) +
                s.a1 * (0.5 * u3 - u4 + 0.5 * u5) + s.v1 * (-4 * u3 + 7 * u4 - 3 * u5) + s.q1 * (10 * u3 - 15 * u4 + 6 * u5)
        } else {
            val dq = s.q0 * (-30 * u2 + 60 * u3 - 30 * u4) + s.v0 * (1 - 18 * u2 + 32 * u3 - 15 * u4) + s.a0 * (u - 4.5 * u2 + 6 * u3 - 2.5 * u4) +
                s.a1 * (1.5 * u2 - 4 * u3 + 2.5 * u4) + s.v1 * (-12 * u2 + 28 * u3 - 15 * u4) + s.q1 * (30 * u2 - 60 * u3 + 30 * u4)
            dq * du / T
        }
    }

    /** Fader: 1 abierto, 0 cerrado (cerrado antes y después del plan). */
    fun fader(b: Double): Int {
        if (segs.isEmpty() || b < start || b >= end) return 0
        if (edgeT.isEmpty() || b < edgeT[0]) return f0
        var lo = 0; var hi = edgeT.size - 1
        while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (edgeT[m] <= b) lo = m else hi = m - 1 }
        return edgeS[lo].toInt()
    }

    fun at(b: Double) = Ref(pos(b), fader(b), vel(b))

    companion object { val EMPTY = Curve(emptyArray(), DoubleArray(0), ByteArray(0), 0) }
}

/** Cuánto varía cada repetición. */
class Style(
    val timeMs: DoubleArray,   // tiempo fuerte, tiempo débil, contratiempo (máximo, ms)
    val len: Double,           // largo del trazo (fracción)
    val cue: Double,           // vuelta al cue (fracción)
    val faderMs: Double,       // corte del fader (ms)
    val shape: Double,         // sesgo de la curva
    val accel: Double,         // fuerza del giro
    val over: Double,          // variación del sobrepaso
    val bounce: Double,        // probabilidad de rebote del fader
)

object Human {
    const val ALPHA = 7.0                 // aceleración del giro (normalizada): mezcla de jerk mínimo con lineal
    private const val MAX_A = 11.0

    val NATURAL = Style(doubleArrayOf(8.0, 10.0, 12.0), 0.08, 0.015, 5.0, 0.04, 1.5, 0.3, 0.2)
    val SUELTA = Style(doubleArrayOf(16.0, 20.0, 24.0), 0.16, 0.03, 11.0, 0.07, 2.5, 0.5, 0.35)

    private val CUT_FAMS = setOf("Chirps", "Flares", "Pro")
    private val BOUNCE_FAMS = setOf("Transformers", "Flares", "Pro")

    /** La guía: forma humana, sin variación. */
    fun ideal(timed: List<Timed>, bpm: Double) = build(timed, bpm, null, 0L, false)

    /** Lo de antes: rectas, giro instantáneo y cortes exactos. */
    fun robot(timed: List<Timed>, bpm: Double) = build(timed, bpm, null, 0L, true)

    /** Cómo toca la app (demo y manos automáticas) según Ajustes › Demo. */
    fun perform(timed: List<Timed>, bpm: Double, style: String, seed: Long): Curve = when (style) {
        "robot" -> robot(timed, bpm)
        "suelta" -> build(timed, bpm, SUELTA, seed, false)
        else -> build(timed, bpm, NATURAL, seed, false)
    }

    internal fun contiguous(timed: List<Timed>, i: Int) =
        i > 0 && i < timed.size && abs(timed[i].b0 - timed[i - 1].b1) < 1e-6 && abs(timed[i].p0 - timed[i - 1].p1) < 1e-6

    internal fun dirOf(s: Timed) = if (abs(s.p1 - s.p0) < 1e-6) 0 else sign(s.p1 - s.p0).toInt()

    /** Giro (cambio de dirección) entre el trazo i-1 y el i. */
    internal fun reversal(timed: List<Timed>, i: Int): Boolean {
        if (!contiguous(timed, i)) return false
        val a = dirOf(timed[i - 1]); val b = dirOf(timed[i])
        return a != 0 && b != 0 && a == -b
    }

    /** Sesgo de la curva: el empuje adelanta, la vuelta se toma su tiempo; si hay corte en el trazo, la mano frena antes. */
    private fun skew(s: Timed): Double {
        val d = dirOf(s)
        if (d == 0) return 0.0
        if (s.s.fam in CUT_FAMS && s.mask.indexOf("10") >= 0) return 0.16
        return if (d > 0) 0.08 else -0.03
    }

    /** Sobrepaso en los giros rápidos (fracción del recorrido). */
    private fun overshoot(durMs: Double) = when {
        durMs <= 180 -> 0.035
        durMs >= 450 -> 0.0
        else -> 0.035 * (450 - durMs) / 270
    }

    private fun g(r: Random) = r.nextGaussian().coerceIn(-2.0, 2.0) / 2   // ~[-1, 1], la mayoría cerca de 0

    private fun build(timed: List<Timed>, bpm: Double, st: Style?, seed: Long, lin: Boolean): Curve {
        val n = timed.size
        if (n == 0) return Curve.EMPTY
        val r = Random(seed)
        val msB = bpm / 60000.0
        val T0 = DoubleArray(n); val T1 = DoubleArray(n); val Q0 = DoubleArray(n); val Q1 = DoubleArray(n)

        fun timeJ(b: Double, room: Double): Double {
            if (st == null) return 0.0
            val frac = b - floor(b)
            val j = if (frac > 1e-6 && frac < 1 - 1e-6) st.timeMs[2] else if ((floor(b + 1e-6).toInt() % 2 + 2) % 2 == 0) st.timeMs[0] else st.timeMs[1]
            return (g(r) * j * msB).coerceIn(-room, room)
        }

        // nudos: tiempos y posiciones (con sobrepaso y variación)
        for (i in 0 until n) {
            val s = timed[i]
            if (contiguous(timed, i)) { T0[i] = T1[i - 1]; Q0[i] = Q1[i - 1] }
            else { T0[i] = s.b0 + timeJ(s.b0, 0.2 * s.dur); Q0[i] = s.p0 }
            val nx = if (i + 1 < n && contiguous(timed, i + 1)) timed[i + 1] else null
            val room = 0.2 * min(s.dur, nx?.dur ?: s.dur)
            T1[i] = s.b1 + timeJ(s.b1, room)
            val d = dirOf(s)
            if (d == 0) { Q1[i] = Q0[i]; continue }
            val amp = abs(s.p1 - s.p0)
            var q = s.p1
            if (!lin && nx != null && dirOf(nx) == -d) {
                var o = overshoot(s.dur / msB)
                if (st != null) o *= 1 + st.over * g(r)
                q += d * o * amp
            }
            if (st != null) q += (if (abs(s.p1) < 1e-6) st.cue else st.len) * amp * g(r)
            Q1[i] = q
        }

        // condiciones de borde: velocidad y aceleración en cada nudo
        val V0 = DoubleArray(n); val A0 = DoubleArray(n); val V1 = DoubleArray(n); val A1 = DoubleArray(n)
        for (i in 1 until n) {
            if (!contiguous(timed, i)) continue
            val a = timed[i - 1]; val b = timed[i]
            val da = dirOf(a); val db = dirOf(b)
            val Ta = T1[i - 1] - T0[i - 1]; val Tb = T1[i] - T0[i]
            val Aa = abs(Q1[i - 1] - Q0[i - 1]); val Ab = abs(Q1[i] - Q0[i])
            if (da != 0 && db != 0 && da == -db) {
                val alpha = ALPHA + (if (st != null) st.accel * g(r) else 0.0)
                val acc = -da * alpha * min(Aa / (Ta * Ta), Ab / (Tb * Tb))
                A1[i - 1] = acc; A0[i] = acc
            } else if (da != 0 && da == db) {
                val v = ((Q1[i - 1] - Q0[i - 1]) / Ta + (Q1[i] - Q0[i]) / Tb) / 2
                V1[i - 1] = v; V0[i] = v
            }
        }

        val segs = Array(n) { i ->
            val T = T1[i] - T0[i]
            var k = if (lin) 0.0 else skew(timed[i])
            if (st != null && k != 0.0) k += st.shape * g(r)
            k = k.coerceIn(-0.3, 0.3)
            val d0 = 1 + k; val d1 = 1 - k
            val D = abs(Q1[i] - Q0[i]).coerceAtLeast(1e-9)
            // a unidades normalizadas (x en 0..1), compensando el sesgo
            val a0 = (A0[i] * T * T / (d0 * d0)).let { it.coerceIn(-MAX_A * D, MAX_A * D) }
            val a1 = (A1[i] * T * T / (d1 * d1)).let { it.coerceIn(-MAX_A * D, MAX_A * D) }
            Curve.CSeg(T0[i], T1[i], Q0[i], Q1[i], V0[i] * T / d0, a0, V1[i] * T / d1, a1, k, lin)
        }

        // fader: los cortes van con la mano del disco (mismo mapa de tiempo) más su propia variación
        val et = ArrayList<Double>(); val es = ArrayList<Byte>()
        var state = timed[0].mask[0] - '0'
        val f0 = state
        val nominal = ArrayList<Triple<Double, Int, Int>>()   // tiempo nominal, estado nuevo, trazo
        for (i in 0 until n) {
            val s = timed[i]; val m = s.mask.length
            for (k in 0 until m) {
                val want = s.mask[k] - '0'
                if (want != state) { nominal.add(Triple(s.b0 + s.dur * k / m, want, i)); state = want }
            }
        }
        for ((j, e) in nominal.withIndex()) {
            val (tn, to, i) = e
            val s = timed[i]
            var t = T0[i] + (tn - s.b0) / s.dur * (T1[i] - T0[i])
            if (st != null) {
                val prev = if (j > 0) tn - nominal[j - 1].first else 1.0
                val next = if (j + 1 < nominal.size) nominal[j + 1].first - tn else 1.0
                t += (g(r) * st.faderMs * msB).coerceIn(-0.3 * prev, 0.3 * next)
            }
            if (et.isNotEmpty() && t <= et[et.size - 1]) t = et[et.size - 1] + 1e-5
            et.add(t); es.add(to.toByte())
            // rebote corto del fader al cerrar, en transformers y flares
            if (st != null && to == 0 && s.s.fam in BOUNCE_FAMS && r.nextDouble() < st.bounce) {
                val next = if (j + 1 < nominal.size) nominal[j + 1].first - tn else 1.0
                val b0 = (2 + 2 * r.nextDouble()) * msB; val b1 = b0 + (3 + 3 * r.nextDouble()) * msB
                if (next > b1 + 15 * msB) { et.add(t + b0); es.add(1); et.add(t + b1); es.add(0) }
            }
        }
        return Curve(segs, et.toDoubleArray(), es.toByteArray(), f0)
    }
}
