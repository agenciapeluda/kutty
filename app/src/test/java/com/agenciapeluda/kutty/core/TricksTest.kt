package com.agenciapeluda.kutty.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconocimiento de trucos (Libre) con las ejecuciones simuladas de la 0.4:
 * robot, Natural y Suelta de cada técnica de las familias que reconoce.
 */
class TricksTest {
    companion object {
        /** Técnica base → nombre que tiene que salir. */
        val EXPECT = mapOf(
            "baby" to "Baby", "forward" to "Forward", "backward" to "Backward",
            "tear" to "Tear", "teardrop" to "Tear", "teardouble" to "Tear", "teartriple" to "Tear",
            "chirp" to "Chirp", "stab" to "Stab",
            "transformer" to "Transformer", "transformerfwd" to "Transformer",
            "flare1" to "Flare 1 click", "flare2" to "Flare 2 clicks", "flare3" to "Flare 3 clicks",
            "orbit1" to "Orbit 1 click", "orbit2" to "Orbit 2 clicks", "orbit3" to "Orbit 3 clicks",
            "twiddle" to "Twiddle", "crab3" to "Crab 3 dedos", "crab4" to "Crab 4 dedos",
            "scribble" to "Scribble",
        )
        val SEEDS = 1L..6L

        fun base(t: Tech) = t.id.substringBefore('-')

        /** El trazo de una ejecución, con muestras cada 1 ms como las del motor. */
        fun trace(timed: List<Timed>, bpm: Double, pos: (Double) -> Double, fad: (Double) -> Int): Trace {
            val t0 = timed.first().b0 - 0.4; val t1 = timed.last().b1 + 0.5
            val tr = Trace(1 shl 16); val step = bpm / 60000.0
            var b = t0
            while (b < t1) { tr.add(b * 60 / bpm, b, b, pos(b).toFloat(), fad(b).toByte()); b += step }
            return tr
        }

        /** Repeticiones: cada ida del plan (en scribble, cada trazo). Cuántas se reconocieron bien. */
        fun score(t: Tech, timed: List<Timed>, bpm: Double, tricks: List<Trick>): Pair<Int, Int> {
            val want = EXPECT.getValue(base(t))
            val reps = if (want == "Scribble") timed.filter { Human.dirOf(it) != 0 }
            else timed.filterIndexed { i, s -> Human.dirOf(s) == 1 && !(i > 0 && Human.dirOf(timed[i - 1]) == 1 && Human.contiguous(timed, i)) }
            var ok = 0
            for (s in reps) {
                val mid = (s.b0 + s.dur / 2) * 60 / bpm
                if (tricks.any { it.name == want && it.t0 - 0.02 <= mid && mid <= it.t1 + 0.02 }) ok++
            }
            return ok to reps.size
        }
    }

    private val techs = Techniques.all.filter { it.family !in setOf("Combos", "Rutinas", "Batalla") && base(it) in EXPECT }

    @Test
    fun reconoceCadaTecnica() {
        val fails = ArrayList<String>()
        val tot = HashMap<String, IntArray>()
        for (t in techs) {
            val bpm = t.bpm.toDouble()
            val timed = Calibration.plan(t)
            for (style in listOf("robot", "natural", "suelta")) {
                var ok = 0; var n = 0
                val seeds = if (style == "robot") 1L..1L else SEEDS
                val seen = HashMap<String, Int>()
                for (seed in seeds) {
                    val c = Human.perform(timed, bpm, style, seed * 31337 + t.id.hashCode())
                    val tricks = Tricks.analyze(trace(timed, bpm, c::pos, c::fader))
                    tricks.forEach { seen[it.name] = (seen[it.name] ?: 0) + 1 }
                    val (o, k) = score(t, timed, bpm, tricks); ok += o; n += k
                }
                tot.getOrPut(style) { IntArray(2) }.let { it[0] += ok; it[1] += n }
                val pct = if (n == 0) 100 else 100 * ok / n
                val line = "${t.id} [$style]: $pct% ($ok/$n) · salió ${seen.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}×${it.value}" }}"
                if (pct < 85 && style != "suelta") fails += line
                if (pct < 85) println("BAJO  $line")
            }
        }
        tot.forEach { (k, v) -> println("Reconocimiento $k: ${100.0 * v[0] / v[1]}% (${v[0]}/${v[1]})") }
        fails.forEach { println("FALLA: $it") }
        assertTrue("Reconocimiento: ${fails.size} técnicas por debajo del 85 %. Primera: ${fails.firstOrNull()}", fails.isEmpty())
    }

    /** Un trazo sin fader (solo baby) nunca es transformer ni flare. */
    @Test
    fun babySinFaderNoEsTransformerNiFlare() {
        val bad = ArrayList<String>()
        val forbidden = listOf("Transformer", "Flare", "Orbit", "Twiddle", "Crab")
        for (t in Techniques.all.filter { base(it) in setOf("baby", "tip", "drag", "scribble") && it.family == "Base" }) {
            val bpm = t.bpm.toDouble()
            val timed = Calibration.plan(t)
            for (style in listOf("robot", "natural", "suelta")) for (seed in SEEDS) for (fad in listOf(1, 0)) {
                val c = Human.perform(timed, bpm, style, seed * 7 + t.id.hashCode())
                val tricks = Tricks.analyze(trace(timed, bpm, c::pos) { fad })
                tricks.filter { k -> forbidden.any { k.name.startsWith(it) } }.forEach { bad += "${t.id} [$style, fader ${if (fad == 1) "abierto" else "cerrado"}]: $it" }
            }
        }
        bad.take(20).forEach { println("FALLA: $it") }
        assertTrue("Baby sin fader reconocido como truco de fader: ${bad.size}. Primero: ${bad.firstOrNull()}", bad.isEmpty())
    }

    /** Cortes limpios y con rebote: el rebote de la ejecución Suelta se nota. */
    @Test
    fun cortesLimpiosYRebotes() {
        val t = Techniques.byId.values.first { base(it) == "flare2" }
        val bpm = t.bpm.toDouble(); val timed = Calibration.plan(t)
        val robot = Human.perform(timed, bpm, "robot", 1)
        val r = Tricks.analyze(trace(timed, bpm, robot::pos, robot::fader))
        val cuts = r.sumOf { it.cuts }; val clean = r.sumOf { it.clean }
        println("Flare 2 robot: $clean/$cuts cortes limpios")
        assertTrue("El robot corta limpio", cuts > 0 && clean == cuts)
        var bounced = 0
        for (seed in SEEDS) {
            val c = Human.perform(timed, bpm, "suelta", seed)
            bounced += Tricks.analyze(trace(timed, bpm, c::pos, c::fader)).sumOf { k -> k.cutQ.count { it != CutQ.CLEAN } }
        }
        println("Flare 2 suelta: $bounced cortes con rebote")
        assertTrue("Los rebotes de Suelta se detectan", bounced > 0)
    }

    /** En la grilla: la ejecución robot cae siempre en semicorcheas o tresillos. */
    @Test
    fun grilla() {
        var on = 0; var n = 0
        for (t in techs) {
            val bpm = t.bpm.toDouble(); val timed = Calibration.plan(t)
            val c = Human.perform(timed, bpm, "natural", 3)
            for (k in Tricks.analyze(trace(timed, bpm, c::pos, c::fader))) { n++; if (k.grid == "1/16" || k.grid == "tresillo") on++ }
        }
        println("Grilla natural: $on/$n")
        assertTrue("En grilla ≥ 85 %: $on/$n", on >= 0.85 * n)
    }
}

class CutWatchTest {
    @Test
    fun cortesEnVivo() {
        val got = ArrayList<Byte>()
        val w = CutWatch { _, _, _, q -> got += q }
        // abierto, corte limpio, abre; corte con rebote; corte con dos rebotes; cierre de 5 ms (temblor)
        val f = StringBuilder()
        f.append("1".repeat(50)).append("0".repeat(60)).append("1".repeat(50))
        f.append("0".repeat(3)).append("1".repeat(4)).append("0".repeat(60)).append("1".repeat(50))
        f.append("0".repeat(3)).append("1".repeat(4)).append("0".repeat(3)).append("1".repeat(3)).append("0".repeat(60)).append("1".repeat(50))
        f.append("0".repeat(5)).append("1".repeat(60))
        f.forEachIndexed { i, c -> w.feed(i / 1000.0, 0.0, 0.0, c - '0') }
        assertTrue("cortes: ${got.toList()}", got.toList() == listOf(CutQ.CLEAN, CutQ.BOUNCE, CutQ.DIRTY, CutQ.DIRTY))
    }
}
