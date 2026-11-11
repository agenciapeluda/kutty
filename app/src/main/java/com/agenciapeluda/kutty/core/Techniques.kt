package com.agenciapeluda.kutty.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Un trazo: duración en tiempos, posición del disco (0 = cue, 1 = un cuarto de vuelta) y fader ("1" abierto, "0" cerrado, en partes iguales). */
/** fam: familia de la técnica de origen (le da carácter a la curva humana). */
data class Stroke(val dur: Double, val p0: Double, val p1: Double, val mask: String, val fam: String = "")

class Timed(val s: Stroke, val b0: Double, val demo: Boolean = false) {
    val b1 get() = b0 + s.dur
    val dur get() = s.dur
    val p0 get() = s.p0
    val p1 get() = s.p1
    val mask get() = s.mask
    var g: Grade? = null
}

data class Tech(
    val id: String, val name: String, val short: String, val family: String, val level: Int,
    val rhythm: String, val beats: Int, val strokes: List<Stroke>, val desc: String, val steps: List<String>, val bpm: Int,
)

private fun S(dur: Double, p0: Double, p1: Double, mask: String) = Stroke(dur, p0, p1, mask)

private class Base(
    val key: String, val name: String, val family: String, val level: Int, val rhythms: List<Double>,
    val desc: String, val steps: List<String>, val fixedName: String? = null, val clicks: Boolean = false,
    val build: (Double) -> List<Stroke>,
)

object Techniques {
    private val bases = listOf(
        Base("baby", "Baby", "Base", 1, listOf(1.0, 0.5, 1 / 3.0, 0.25),
            "El scratch madre: el fader queda abierto y solo movés el disco hacia adelante y hacia atrás, a tempo.",
            listOf("Fader abierto todo el tiempo.", "Empujá el disco un cuarto de vuelta hacia adelante.", "Volvé al cue con la misma velocidad.", "Cada ida y cada vuelta caen en la subdivisión.")) { u -> listOf(S(u, 0.0, 1.0, "1"), S(u, 1.0, 0.0, "1")) },
        Base("tip", "Tip", "Base", 2, listOf(0.5, 0.25),
            "Baby muy corto: solo la punta del sonido. Entrena control fino del disco.",
            listOf("Fader abierto.", "Movimientos cortos, menos de un octavo de vuelta.", "Mantené el ritmo sin agrandar el trazo.")) { u -> listOf(S(u, 0.0, 0.35, "1"), S(u, 0.35, 0.0, "1")) },
        Base("drag", "Drag", "Base", 1, listOf(1.0, 0.5),
            "Arrastre lento del disco, ida y vuelta, que estira el sonido. Control de velocidad constante.",
            listOf("Fader abierto.", "Arrastrá el disco lento y parejo hacia adelante.", "Volvé igual de lento.")) { u -> listOf(S(2 * u, 0.0, 1.0, "1"), S(2 * u, 1.0, 0.0, "1")) },
        Base("scribble", "Scribble", "Base", 3, listOf(0.125),
            "Vibración rapidísima del disco con la mano tensa: un trino de trazos mínimos.",
            listOf("Fader abierto.", "Tensá el antebrazo y hacé vibrar el disco.", "Trazos mínimos y muy rápidos, sin perder el pulso."), fixedName = "Scribble") { u -> List(8) { i -> if (i % 2 == 1) S(u, 0.15, 0.0, "1") else S(u, 0.0, 0.15, "1") } },
        Base("scribblecut", "Scribble cortado", "Base", 4, listOf(0.125),
            "Scribble con el fader marcando corcheas: medio tiempo suena, medio tiempo no.",
            listOf("Hacé el scribble de forma continua.", "Abrí el fader en la primera mitad de cada tiempo.", "Cerralo en la segunda mitad."), fixedName = "Scribble cortado") { u -> List(8) { i -> S(u, if (i % 2 == 1) 0.15 else 0.0, if (i % 2 == 1) 0.0 else 0.15, if (i < 4) "1" else "0") } },
        Base("forward", "Forward", "Cortes", 2, listOf(1.0, 0.5, 1 / 3.0, 0.25),
            "Solo suena la ida: el fader se abre para empujar y se cierra para volver.",
            listOf("Abrí el fader y empujá el disco.", "Cerrá el fader antes de volver.", "Volvé al cue en silencio.")) { u -> listOf(S(u, 0.0, 1.0, "1"), S(u, 1.0, 0.0, "0")) },
        Base("backward", "Backward", "Cortes", 2, listOf(1.0, 0.5, 1 / 3.0, 0.25),
            "Solo suena la vuelta: empujás en silencio y abrís el fader para tirar hacia atrás.",
            listOf("Fader cerrado, empujá el disco.", "Abrí el fader al volver.", "Cerralo al llegar al cue.")) { u -> listOf(S(u, 0.0, 1.0, "0"), S(u, 1.0, 0.0, "1")) },
        Base("chop", "Chop", "Cortes", 3, listOf(0.5, 0.25),
            "Forward recortado: el fader solo deja pasar el ataque del sonido y corta en seco.",
            listOf("Abrí el fader al empezar a empujar.", "Cerralo a mitad del trazo.", "Volvé en silencio.")) { u -> listOf(S(u, 0.0, 1.0, "10"), S(u, 1.0, 0.0, "0")) },
        Base("stab", "Stab", "Cortes", 3, listOf(1.0, 0.5),
            "Golpe corto y rápido hacia adelante, como un puñetazo de sonido, y silencio.",
            listOf("Empujá rápido y corto con el fader abierto.", "Cerrá y volvé al cue.", "Esperá el próximo golpe con el disco quieto.")) { u -> listOf(S(0.35 * u, 0.0, 0.8, "1"), S(0.65 * u, 0.8, 0.0, "0"), S(u, 0.0, 0.0, "0")) },
        Base("tear", "Tear", "Tears", 2, listOf(1.0, 0.5),
            "La vuelta se parte en dos con una pausa: un sonido hacia adelante y dos hacia atrás.",
            listOf("Fader abierto.", "Empujá el disco de una.", "Volvé hasta la mitad, frená un instante.", "Terminá de volver al cue.")) { u -> listOf(S(u, 0.0, 1.0, "1"), S(0.45 * u, 1.0, 0.5, "1"), S(0.1 * u, 0.5, 0.5, "1"), S(0.45 * u, 0.5, 0.0, "1")) },
        Base("teardrop", "Tear adelante", "Tears", 3, listOf(1.0, 0.5),
            "Tear invertido: la ida se parte en dos y la vuelta es de una.",
            listOf("Fader abierto.", "Empujá hasta la mitad y frená.", "Terminá la ida.", "Volvé de una.")) { u -> listOf(S(0.45 * u, 0.0, 0.5, "1"), S(0.1 * u, 0.5, 0.5, "1"), S(0.45 * u, 0.5, 1.0, "1"), S(u, 1.0, 0.0, "1")) },
        Base("teardouble", "Clover tear", "Tears", 3, listOf(1.0, 0.5),
            "Ida y vuelta partidas: cuatro sonidos por ciclo con dos pausas cortas.",
            listOf("Fader abierto.", "Ida en dos partes.", "Vuelta en dos partes.", "Las pausas son cortas y parejas.")) { u ->
            listOf(S(0.45 * u, 0.0, 0.5, "1"), S(0.1 * u, 0.5, 0.5, "1"), S(0.45 * u, 0.5, 1.0, "1"), S(0.45 * u, 1.0, 0.5, "1"), S(0.1 * u, 0.5, 0.5, "1"), S(0.45 * u, 0.5, 0.0, "1"))
        },
        Base("teartriple", "Tear triple", "Tears", 4, listOf(1.0),
            "La vuelta se parte en tres: entrena frenadas precisas con el disco.",
            listOf("Fader abierto.", "Empujá de una.", "Volvé en tres partes iguales con dos frenadas.")) { u ->
            listOf(S(u, 0.0, 1.0, "1"), S(0.27 * u, 1.0, 0.67, "1"), S(0.1 * u, 0.67, 0.67, "1"), S(0.26 * u, 0.67, 0.33, "1"), S(0.1 * u, 0.33, 0.33, "1"), S(0.27 * u, 0.33, 0.0, "1"))
        },
        Base("chirp", "Chirp", "Chirps", 4, listOf(1.0, 0.5, 1 / 3.0, 0.25),
            "Disco y fader se mueven juntos: cerrás mientras empujás y abrís mientras volvés. Suena como un pájaro.",
            listOf("Empezá con el fader abierto.", "Empujá y cerrá el fader en el mismo movimiento.", "Volvé y abrí el fader a la vez.", "La mano del fader y la del disco van espejadas.")) { u -> listOf(S(u, 0.0, 1.0, "10"), S(u, 1.0, 0.0, "01")) },
        Base("chirpinv", "Chirp invertido", "Chirps", 5, listOf(0.5, 0.25),
            "El chirp al revés: abrís al final de la ida y cerrás al principio de la vuelta.",
            listOf("Fader cerrado al empujar, abrilo al final.", "Volvé con el fader abierto y cerralo enseguida.")) { u -> listOf(S(u, 0.0, 1.0, "01"), S(u, 1.0, 0.0, "10")) },
        Base("transformer", "Transformer", "Transformers", 4, listOf(2.0, 3.0, 4.0),
            "Disco lento y parejo mientras el fader marca pulsos rítmicos: el clásico sonido entrecortado.",
            listOf("Mové el disco lento: un tiempo de ida, uno de vuelta.", "Tocá el fader abriéndolo en cada pulso.", "El fader vuelve siempre a cerrado."), clicks = true) { c -> listOf(S(1.0, 0.0, 1.0, "01".repeat(c.toInt())), S(1.0, 1.0, 0.0, "01".repeat(c.toInt()))) },
        Base("transformerfwd", "Transformer de ida", "Transformers", 4, listOf(2.0),
            "Transformer solo en la ida; la vuelta va en silencio.",
            listOf("Ida lenta con pulsos de fader.", "Vuelta en silencio con el fader cerrado."), fixedName = "Transformer de ida", clicks = true) { c -> listOf(S(1.0, 0.0, 1.0, "01".repeat(c.toInt())), S(1.0, 1.0, 0.0, "0")) },
        Base("flare1", "Flare 1 click", "Flares", 5, listOf(1.0, 0.5),
            "Fader abierto y un click (cierre rapidísimo) en medio de la ida: dos sonidos en un solo empuje.",
            listOf("Fader abierto.", "Empujá el disco.", "A mitad de la ida, cerrá y abrí el fader de un toque.", "Volvé con el fader abierto.")) { u -> listOf(S(u, 0.0, 1.0, "11011"), S(u, 1.0, 0.0, "1")) },
        Base("orbit1", "Orbit 1 click", "Flares", 6, listOf(1.0, 0.5, 0.25),
            "Flare de 1 click en la ida y en la vuelta: cuatro sonidos por ciclo.",
            listOf("Fader abierto.", "Un click a mitad de la ida.", "Un click a mitad de la vuelta.")) { u -> listOf(S(u, 0.0, 1.0, "11011"), S(u, 1.0, 0.0, "11011")) },
        Base("flare2", "Flare 2 clicks", "Flares", 7, listOf(1.0, 0.5),
            "Dos clicks en la ida: tres sonidos en un empuje. La base del flare moderno.",
            listOf("Fader abierto.", "Empujá el disco parejo.", "Dos clicks repartidos en la ida.", "Volvé con el fader abierto.")) { u -> listOf(S(u, 0.0, 1.0, "1101011"), S(u, 1.0, 0.0, "1")) },
        Base("orbit2", "Orbit 2 clicks", "Flares", 8, listOf(1.0, 0.5, 1 / 3.0),
            "Dos clicks en la ida y dos en la vuelta: seis sonidos por ciclo.",
            listOf("Fader abierto.", "Dos clicks en la ida.", "Dos clicks en la vuelta.", "Mantené los clicks parejos.")) { u -> listOf(S(u, 0.0, 1.0, "1101011"), S(u, 1.0, 0.0, "1101011")) },
        Base("flare3", "Flare 3 clicks", "Flares", 8, listOf(1.0),
            "Tres clicks en la ida: cuatro sonidos en un solo empuje.",
            listOf("Fader abierto.", "Ida pareja con tres clicks.", "Volvé abierto.")) { u -> listOf(S(u, 0.0, 1.0, "110101011"), S(u, 1.0, 0.0, "1")) },
        Base("orbit3", "Orbit 3 clicks", "Flares", 9, listOf(1.0),
            "Tres clicks en cada dirección. Exige un fader muy suelto.",
            listOf("Fader abierto.", "Tres clicks en la ida.", "Tres clicks en la vuelta.")) { u -> listOf(S(u, 0.0, 1.0, "110101011"), S(u, 1.0, 0.0, "110101011")) },
        Base("twiddle", "Twiddle", "Crabs", 7, listOf(1.0, 0.5),
            "Dos dedos golpean el fader en sucesión rápida, abriendo dos veces por trazo.",
            listOf("Fader cerrado, apoyado en el pulgar.", "Golpeá el fader con dos dedos seguidos.", "Cada golpe abre un instante.")) { u -> listOf(S(u, 0.0, 1.0, "01010"), S(u, 1.0, 0.0, "01010")) },
        Base("crab3", "Crab 3 dedos", "Crabs", 8, listOf(1.0, 0.5),
            "Tres dedos golpean el fader contra el pulgar: tres aperturas rapidísimas por trazo.",
            listOf("Fader cerrado contra el pulgar.", "Golpeá con tres dedos en cascada.", "Uno por trazo, ida y vuelta.")) { u -> listOf(S(u, 0.0, 1.0, "0101010"), S(u, 1.0, 0.0, "0101010")) },
        Base("crab4", "Crab 4 dedos", "Crabs", 9, listOf(1.0, 0.5),
            "El crab completo: cuatro dedos, cuatro aperturas por trazo.",
            listOf("Fader cerrado contra el pulgar.", "Cascada de cuatro dedos.", "Ida y vuelta parejas.")) { u -> listOf(S(u, 0.0, 1.0, "010101010"), S(u, 1.0, 0.0, "010101010")) },

        // ---- Pro: técnicas de nivel batalla (patrones según su descripción en escuelas de turntablism) ----
        Base("ogflare", "OG flare", "Pro", 6, listOf(1.0, 0.5),
            "El primer flare de dos clicks, de D-Styles: en la ida un click al final, como un chirp; en la vuelta, un flare de 1 click que corta el sonido al medio.",
            listOf("Fader abierto.", "Empujá y hacé un click justo al final de la ida.", "En la vuelta, un click al medio.", "El fader nunca queda cerrado: son clicks.")) { u -> listOf(S(u, 0.0, 1.0, "1110"), S(u, 1.0, 0.0, "11011")) },
        Base("ogflarerev", "OG flare invertido", "Pro", 6, listOf(1.0, 0.5),
            "El OG flare dado vuelta: el click al medio va en la ida y el click del final en la vuelta.",
            listOf("Fader abierto.", "Click al medio de la ida.", "Click justo al final de la vuelta.")) { u -> listOf(S(u, 0.0, 1.0, "11011"), S(u, 1.0, 0.0, "1110")) },
        Base("chirpflare", "Chirp flare", "Pro", 6, listOf(0.5, 0.25),
            "Alterna un chirp con un flare de 1 click, sin cortar: el combo clásico de D-Styles.",
            listOf("Primer ida y vuelta: chirp.", "Segunda ida y vuelta: flare de 1 click.", "Sin pausa entre uno y otro.")) { u -> listOf(S(u, 0.0, 1.0, "10"), S(u, 1.0, 0.0, "01"), S(u, 0.0, 1.0, "11011"), S(u, 1.0, 0.0, "1")) },
        Base("chirp2flare", "Chirp + 2 click flare", "Pro", 7, listOf(0.5),
            "Un chirp seguido de un flare de 2 clicks.",
            listOf("Ida y vuelta: chirp.", "Ida y vuelta: 2 click flare.", "Encadenados a tempo.")) { u -> listOf(S(u, 0.0, 1.0, "10"), S(u, 1.0, 0.0, "01"), S(u, 0.0, 1.0, "1101011"), S(u, 1.0, 0.0, "1")) },
        Base("boomerang", "Boomerang", "Pro", 8, listOf(1.0, 0.5),
            "El 2 click flare retrasado, de DJ Flare: el mismo movimiento de fader que el orbit de 2 clicks pero empezando cerrado, sobre babies continuos. Seis notas por ciclo, tres por trazo.",
            listOf("Fader cerrado al empezar.", "Disco en baby, ida y vuelta parejas.", "Fader en 2 clicks continuos, corrido medio golpe.", "Tres notas en la ida, tres en la vuelta, sin pausa.")) { u -> listOf(S(u, 0.0, 1.0, "011011011"), S(u, 1.0, 0.0, "011011011")) },
        Base("delayed3", "3 click retrasado", "Pro", 9, listOf(1.0),
            "El orbit de 3 clicks con el fader corrido: los clicks quedan a caballo del cambio de dirección.",
            listOf("Disco en baby.", "Fader en 3 clicks continuos.", "Empezá el ciclo de fader medio click tarde.")) { u -> listOf(S(u, 0.0, 1.0, "010101111"), S(u, 1.0, 0.0, "010101111")) },
        Base("boomflare", "Boom flare", "Pro", 9, listOf(0.5),
            "Boomerang más 2 click flare: un patrón de 12 notas.",
            listOf("Primer ida y vuelta: boomerang (6 notas).", "Segunda ida y vuelta: orbit de 2 clicks (6 notas).", "Sin cortar el ritmo.")) { u -> listOf(S(u, 0.0, 1.0, "011011011"), S(u, 1.0, 0.0, "011011011"), S(u, 0.0, 1.0, "1101011"), S(u, 1.0, 0.0, "1101011")) },
        Base("military", "Military", "Cortes", 4, listOf(0.5),
            "Babies y stabs combinados con aire de marcha.",
            listOf("Ida y vuelta en baby.", "Un stab corto y silencio.", "Marcado y parejo.")) { u -> listOf(S(u, 0.0, 1.0, "1"), S(u, 1.0, 0.0, "1"), S(0.35 * u, 0.0, 0.8, "1"), S(0.65 * u, 0.8, 0.0, "0"), S(u, 0.0, 0.0, "0")) },
    )
    private val baseMap = bases.associateBy { it.key }

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6
    private fun rName(u: Double) = when {
        near(u, 2.0) -> "blancas"; near(u, 1.0) -> "negras"; near(u, 0.5) -> "corcheas"
        near(u, 1 / 3.0) -> "tresillos"; near(u, 0.25) -> "semicorcheas"; else -> "fusas"
    }
    private fun rLvl(u: Double) = when {
        near(u, 2.0) -> -1; near(u, 1.0) -> 0; near(u, 0.5) -> 1; near(u, 1 / 3.0) -> 1; near(u, 0.25) -> 2; else -> 0
    }
    private fun label(b: Base, u: Double): String = when {
        b.clicks -> when (u.toInt()) { 2 -> "corcheas"; 3 -> "tresillos"; else -> "semicorcheas" }
        b.fixedName != null -> "fusas"
        else -> rName(u)
    }
    private fun lvlOf(b: Base, u: Double): Int = when {
        b.clicks -> when (u.toInt()) { 2 -> 0; 3 -> 1; else -> 2 }
        b.fixedName != null -> 0
        else -> rLvl(u)
    }
    private fun clamp(l: Int) = max(1, min(10, l))
    fun bpmFor(level: Int) = if (level <= 3) 80 else if (level <= 6) 85 else 90

    private fun cycleDur(c: List<Stroke>) = c.sumOf { it.dur }
    private fun fill(cycle: List<Stroke>, beats: Double): List<Stroke> {
        val d = cycleDur(cycle); val out = ArrayList<Stroke>(); var t = 0.0
        while (t < beats - 1e-6) { out.addAll(cycle); t += d }
        require(abs(t - beats) < 1e-6) { "El ciclo no entra en $beats tiempos" }
        return out
    }
    private fun cyc(b: Base, u: Double) = b.build(u).map { it.copy(fam = b.family) }
    private fun part(key: String, u: Double, beats: Double) = fill(cyc(baseMap.getValue(key), u), beats)

    val all: List<Tech> by lazy { build() }
    val byId: Map<String, Tech> by lazy { all.associateBy { it.id } }

    private fun build(): List<Tech> {
        val list = ArrayList<Tech>()
        for (b in bases) for (u in b.rhythms) {
            val cy = cyc(b, u)
            val beats = max(2, ceil(cycleDur(cy) - 1e-6).toInt())
            val level = clamp(b.level + lvlOf(b, u))
            val lab = label(b, u)
            list.add(Tech(b.key + "-" + u.toString().filter { it.isDigit() }.take(4), b.fixedName ?: (b.name + " · " + lab), b.name, b.family, level, lab, beats, fill(cy, beats.toDouble()), b.desc, b.steps, bpmFor(level)))
        }
        val H = 0.5; val Q = 1.0; val T = 1 / 3.0
        val pairs = listOf(
            Triple("baby", H, "forward" to H), Triple("baby", H, "backward" to H), Triple("baby", H, "chirp" to H), Triple("baby", H, "tear" to Q),
            Triple("forward", H, "backward" to H), Triple("forward", H, "chirp" to H), Triple("backward", H, "chirp" to H), Triple("tear", Q, "chirp" to H),
            Triple("tear", Q, "forward" to H), Triple("drag", H, "baby" to 0.25), Triple("stab", H, "forward" to H), Triple("chop", H, "chirp" to H),
            Triple("chirp", H, "chirpinv" to H), Triple("baby", H, "transformer" to 2.0), Triple("chirp", H, "transformer" to 2.0), Triple("transformer", 2.0, "transformer" to 4.0),
            Triple("tear", Q, "transformer" to 2.0), Triple("forward", 0.25, "chop" to 0.25), Triple("teardouble", Q, "chirp" to H), Triple("baby", T, "chirp" to T),
            Triple("forward", T, "backward" to T), Triple("baby", H, "flare1" to Q), Triple("chirp", H, "flare1" to Q), Triple("tear", Q, "flare1" to Q),
            Triple("flare1", Q, "orbit1" to H), Triple("flare1", H, "chirp" to H), Triple("orbit1", Q, "transformer" to 2.0), Triple("flare1", Q, "flare2" to Q),
            Triple("chirp", 0.25, "orbit1" to H), Triple("orbit1", H, "orbit2" to Q), Triple("flare2", Q, "chirp" to H), Triple("flare2", H, "orbit1" to H),
            Triple("orbit2", Q, "transformer" to 4.0), Triple("twiddle", Q, "chirp" to H), Triple("twiddle", H, "flare1" to H), Triple("crab3", Q, "baby" to H),
            Triple("crab3", H, "chirp" to H), Triple("crab3", Q, "flare2" to Q), Triple("crab4", Q, "orbit2" to Q), Triple("flare3", Q, "crab4" to H),
            Triple("orbit3", Q, "crab3" to H), Triple("teartriple", Q, "orbit2" to H), Triple("scribble", 0.125, "transformer" to 3.0), Triple("orbit2", H, "crab4" to H),
        )
        val routines = listOf(
            "Rutina boom bap I" to listOf("baby" to H, "forward" to H, "chirp" to H, "tear" to Q),
            "Rutina boom bap II" to listOf("chirp" to H, "transformer" to 2.0, "forward" to 0.25, "chop" to H),
            "Rutina flares I" to listOf("flare1" to Q, "orbit1" to H, "chirp" to H, "flare2" to Q),
            "Rutina flares II" to listOf("flare2" to Q, "orbit2" to H, "transformer" to 4.0, "orbit1" to H),
            "Rutina crabs" to listOf("twiddle" to H, "crab3" to Q, "chirp" to 0.25, "crab4" to H),
        )
        for ((a, ua, bp) in pairs) {
            val (b, ub) = bp
            val A = baseMap.getValue(a); val B = baseMap.getValue(b)
            val level = clamp(max(A.level + lvlOf(A, ua), B.level + lvlOf(B, ub)) + 1)
            val la = label(A, ua); val lb = label(B, ub)
            list.add(Tech("combo-$a-$b-${list.size}", "${A.name} → ${B.name} · " + (if (la == lb) la else "$la/$lb"), "Combo", "Combos", level, "$la / $lb", 4,
                part(a, ua, 2.0) + part(b, ub, 2.0),
                "Dos tiempos de ${A.name} ($la) y dos de ${B.name} ($lb), sin cortar el ritmo.",
                listOf("Tiempos 1 y 2: ${A.name}.", "Tiempos 3 y 4: ${B.name}.", "El cambio cae justo en el tiempo 3, sin pausa."), bpmFor(level)))
        }
        val battle = listOf(
            "Batalla I" to listOf("ogflare" to H, "boomerang" to H, "crab4" to H, "orbit2" to H),
            "Batalla II" to listOf("chirpflare" to H, "orbit1" to 0.25, "boomerang" to H, "transformer" to 4.0),
            "Batalla III" to listOf("boomflare" to H, "crab3" to H, "orbit2" to T, "chirp" to 0.25),
            "Batalla IV" to listOf("ogflarerev" to H, "delayed3" to Q, "twiddle" to H, "orbit1" to 0.25),
            "Batalla V" to listOf("boomerang" to H, "orbit3" to Q, "crab4" to H, "scribble" to 0.125),
            "Batalla VI" to listOf("flare3" to Q, "boomflare" to H, "orbit2" to T, "crab3" to H),
        )
        battle.forEachIndexed { i, (name, parts) ->
            list.add(Tech("batalla-${i + 1}", name, "Batalla", "Batalla", 10, "2 compases", 8,
                parts.flatMap { (k, u) -> part(k, u, 2.0) },
                "Nivel batalla: encadena " + parts.joinToString(", ") { baseMap.getValue(it.first).name } + " a tempo, sin respirar.",
                parts.mapIndexed { j, (k, u) -> "Tiempos ${j * 2 + 1}–${j * 2 + 2}: ${baseMap.getValue(k).name} (${label(baseMap.getValue(k), u)})." }, 95))
        }
        routines.forEachIndexed { i, (name, parts) ->
            val level = clamp(parts.maxOf { (k, u) -> baseMap.getValue(k).level + lvlOf(baseMap.getValue(k), u) } + 1)
            list.add(Tech("rutina-${i + 1}", name, "Rutina", "Rutinas", level, "2 compases", 8,
                parts.flatMap { (k, u) -> part(k, u, 2.0) },
                "Rutina de dos compases que encadena " + parts.joinToString(", ") { baseMap.getValue(it.first).name } + ".",
                parts.mapIndexed { j, (k, u) -> "Tiempos ${j * 2 + 1}–${j * 2 + 2}: ${baseMap.getValue(k).name} (${label(baseMap.getValue(k), u)})." }, bpmFor(level)))
        }
        return list
    }

    fun timeStrokes(strokes: List<Stroke>, start: Double, demo: Boolean = false): List<Timed> {
        var t = start
        return strokes.map { s -> Timed(s, t, demo).also { t += s.dur } }
    }
}
