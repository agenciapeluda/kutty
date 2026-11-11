package com.agenciapeluda.kutty.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Calibración automática de la puntuación: ejecuciones simuladas de cada técnica
 * contra la curva humana. Si algún umbral falla, el build no sale.
 */
object Calibration {
    val DIFFS = listOf("facil", "normal", "pro")
    val SEEDS = 1L..5L

    class Fail(val tech: String, val diff: String, val scenario: String, val detail: String) {
        override fun toString() = "$tech [$diff] $scenario: $detail"
    }

    class Report(val fails: List<Fail>, val lines: List<String>)

    fun plan(t: Tech): List<Timed> {
        val beats = ceil(max(8, t.beats).toDouble() / t.beats) * t.beats
        val out = ArrayList<Timed>(); var b = 0.0
        while (b < beats - 1e-6) { out += Techniques.timeStrokes(t.strokes, b); b += t.beats }
        return out
    }

    /** Muestras cada 1 ms, como las que llegan del motor. */
    fun samples(t0: Double, t1: Double, bpm: Double, pos: (Double) -> Double, fad: (Double) -> Int): Samples {
        val sm = Samples(); val step = bpm / 60000.0
        var b = t0 - 0.3
        while (b < t1 + 0.4) { sm.add(b, pos(b).toFloat(), fad(b).toByte()); b += step }
        return sm
    }

    fun run(timed: List<Timed>, ref: Curve, sm: Samples, tol: Tol, part: String = "completo"): List<Grade> {
        val lag = LagTracker()
        return timed.indices.map { Grading.grade(timed, it, ref, sm, tol, part, lag) }
    }

    /** Trazos donde un corte corrido 80 ms no cae justo sobre otro corte igual (eso sería el mismo patrón). */
    private fun cutTestable(timed: List<Timed>, i: Int, shiftB: Double, winB: Double): Boolean {
        val all = ArrayList<Pair<Double, Char>>()
        var st = timed[0].mask[0]
        for (s in timed) for (k in s.mask.indices) { if (s.mask[k] != st) { all.add(s.b0 + s.dur * k / s.mask.length to s.mask[k]); st = s.mask[k] } }
        val s = timed[i]
        val mine = all.filter { it.first >= s.b0 - 1e-9 && it.first < s.b1 - 1e-9 }
        if (mine.isEmpty()) return false
        return mine.all { e -> all.none { o -> o !== e && o.second == e.second && abs(o.first - (e.first + shiftB)) < winB + shiftB * 0.25 } }
    }

    fun check(techs: List<Tech> = Techniques.all): Report {
        val fails = ArrayList<Fail>(); val lines = ArrayList<String>()
        var nat = 0; var natP = 0; var loo = 0; var looPG = 0
        var errN = 0; var errP = 0
        for (t in techs) {
            val bpm = t.bpm.toDouble()
            val timed = plan(t)
            val ref = Human.ideal(timed, bpm)
            val t0 = timed.first().b0; val t1 = timed.last().b1
            // forma sana: el disco nunca va para atrás dentro de un trazo
            for (s in timed) {
                val d = Human.dirOf(s); if (d == 0) continue
                for (k in 1 until 40) { val v = ref.vel(s.b0 + s.dur * k / 40); if (v * d < -0.02 * abs(s.p1 - s.p0) / s.dur) { fails += Fail(t.id, "-", "forma", "la guía va para atrás en un trazo"); break } }
            }
            for (diff in DIFFS) {
                val tol = Tol.of(diff, bpm)
                // robot exacto: la guía y las rectas de antes
                for ((name, c) in listOf("guía exacta" to ref, "robot" to Human.robot(timed, bpm))) {
                    val g = run(timed, ref, samples(t0, t1, bpm, c::pos, c::fader), tol)
                    val bad = g.count { it.grade != Grade.PERFECT }
                    if (bad > 0) fails += Fail(t.id, diff, name, "$bad de ${g.size} trazos sin Perfecto (peor ${"%.2f".format(g.minOf { it.final })})")
                }
                // humano Natural: ≥ 90 % Perfecto
                var n = 0; var p = 0
                for (seed in SEEDS) {
                    val c = Human.perform(timed, bpm, "natural", seed * 7919 + t.id.hashCode())
                    val g = run(timed, ref, samples(t0, t1, bpm, c::pos, c::fader), tol)
                    n += g.size; p += g.count { it.grade == Grade.PERFECT }
                }
                nat += n; natP += p
                if (p < 0.9 * n) fails += Fail(t.id, diff, "natural", "${100 * p / n}% Perfecto (mínimo 90%)")
                // humano Suelto: ≥ 60 % Perfecto o Bien
                n = 0; p = 0
                for (seed in SEEDS) {
                    val c = Human.perform(timed, bpm, "suelta", seed * 104729 + t.id.hashCode())
                    val g = run(timed, ref, samples(t0, t1, bpm, c::pos, c::fader), tol)
                    n += g.size; p += g.count { it.grade <= Grade.GOOD }
                }
                loo += n; looPG += p
                if (p < 0.6 * n) fails += Fail(t.id, diff, "suelta", "${100 * p / n}% Perfecto o Bien (mínimo 60%)")
                // errores reales: no pueden dar Perfecto
                val moving = timed.indices.filter { Human.dirOf(timed[it]) != 0 }
                val inv = run(timed, ref, samples(t0, t1, bpm, { -ref.pos(it) }, ref::fader), tol)
                val half = run(timed, ref, samples(t0, t1, bpm, { 0.5 * ref.pos(it) }, ref::fader), tol)
                val sh = 0.08 * bpm / 60
                val cut = run(timed, ref, samples(t0, t1, bpm, ref::pos, { ref.fader(it - sh) }), tol)
                val cutIdx = timed.indices.filter { cutTestable(timed, it, sh, 2.5 * tol.cutB) }
                for ((name, g, idx) in listOf(Triple("dirección invertida", inv, moving), Triple("recorrido a la mitad", half, moving), Triple("corte corrido 80 ms", cut, cutIdx))) {
                    val pf = idx.count { g[it].grade == Grade.PERFECT }
                    errN += idx.size; errP += pf
                    if (pf > 0) fails += Fail(t.id, diff, name, "$pf de ${idx.size} trazos dieron Perfecto")
                }
            }
        }
        lines += "Técnicas: ${techs.size} × ${DIFFS.size} exigencias"
        lines += "Natural: ${if (nat > 0) 100.0 * natP / nat else 0.0}% Perfecto"
        lines += "Suelta: ${if (loo > 0) 100.0 * looPG / loo else 0.0}% Perfecto o Bien"
        lines += "Errores: $errP Perfecto de $errN trazos con error"
        return Report(fails, lines)
    }
}
