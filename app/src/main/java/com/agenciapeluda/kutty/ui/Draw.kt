package com.agenciapeluda.kutty.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.agenciapeluda.kutty.audio.Peaks
import com.agenciapeluda.kutty.audio.REV
import com.agenciapeluda.kutty.audio.UNIT
import com.agenciapeluda.kutty.core.Curve
import com.agenciapeluda.kutty.core.Flash
import com.agenciapeluda.kutty.core.Human
import com.agenciapeluda.kutty.core.Samples
import com.agenciapeluda.kutty.core.Tech
import com.agenciapeluda.kutty.core.Techniques
import com.agenciapeluda.kutty.core.Timed
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
private val dashGrid = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))

fun waveAt(pk: Peaks, sec: Double): FloatArray? {
    val i = (sec * pk.rate).toInt()
    if (i < 0 || i >= pk.len) return null
    return floatArrayOf(pk.low[i] / pk.max, pk.mid[i] / pk.max, pk.high[i] / pk.max)
}

fun DrawScope.waveCol(x: Float, w: Float, cy: Float, hh: Float, v: FloatArray, alpha: Float) {
    val tot = v[0] + v[1] + v[2]
    var a = tot * hh; drawRect(C.waveLow, Offset(x, cy - a), Size(w, a * 2), alpha)
    a = (v[1] + v[2]) * hh; drawRect(C.waveMid, Offset(x, cy - a), Size(w, a * 2), alpha)
    a = v[2] * hh; drawRect(C.waveHigh, Offset(x, cy - a), Size(w, a * 2), alpha)
}

/** Onda de un tramo [s0, s1] en segundos. */
fun DrawScope.waveRange(pk: Peaks, s0: Double, s1: Double, top: Float, h: Float, alpha: Float = 0.9f) {
    val w = size.width; val step = max(1f, density)
    var x = 0f
    while (x < w) {
        waveAt(pk, s0 + (x + step / 2) / w * (s1 - s0))?.let { waveCol(x, step, top + h / 2, h * 0.46f, it, alpha) }
        x += step
    }
}

class TtmOpts(
    val b0: Double, val b1: Double, val now: Double?, val strokes: List<Timed>, val samples: Samples?,
    val flash: List<Flash>? = null, val grid: Boolean = true, val bg: Peaks? = null, val bgSec: ((Double) -> Double?)? = null,
    /** La guía (curva humana) y el ancho de la banda de tolerancia, en tiempos (0 = sin banda). */
    val guide: Curve? = null, val tolB: Double = 0.0,
)

/** Tramo de la curva como trazo continuo. */
private fun curvePath(c: Curve, ta: Double, tb: Double, step: Double, X: (Double) -> Float, Y: (Double) -> Float): Path {
    val p = Path(); var t = ta
    p.moveTo(X(t), Y(c.pos(t)))
    while (t < tb) { t = min(tb, t + step); p.lineTo(X(t), Y(c.pos(t))) }
    return p
}

/** La guía: la curva humana partida donde corta el fader (abierto grueso, cerrado punteado). */
private fun DrawScope.drawGuide(
    strokes: List<Timed>, c: Curve, b0: Double, b1: Double, step: Double, X: (Double) -> Float, Y: (Double) -> Float,
    wOpen: Float, wClosed: Float, dashFx: PathEffect, color: (Timed) -> Color, alphaOpen: (Timed) -> Float, alphaClosed: (Timed) -> Float,
) {
    for (s in strokes) {
        if (s.b1 < b0 || s.b0 > b1) continue
        val n = s.mask.length
        val col = color(s)
        var k = 0
        while (k < n) {
            var e = k + 1
            while (e < n && s.mask[e] == s.mask[k]) e++
            val xa = max(b0, s.b0 + s.dur * k / n); val xb = min(b1, s.b0 + s.dur * e / n)
            if (xb > xa) {
                val path = curvePath(c, xa, xb, step, X, Y)
                if (s.mask[k] == '1') drawPath(path, col, alpha = alphaOpen(s), style = Stroke(wOpen, cap = StrokeCap.Round, join = StrokeJoin.Round))
                else drawPath(path, C.dim, alpha = alphaClosed(s), style = Stroke(wClosed, pathEffect = dashFx))
            }
            k = e
        }
    }
}

fun DrawScope.drawTtm(tm: TextMeasurer, o: TtmOpts, top: Float = 0f) {
    val W = size.width; val H = size.height - top
    val padT = 22f * density; val padB = 18f * density; val ih = H - padT - padB
    fun X(b: Double) = ((b - o.b0) / (o.b1 - o.b0) * W).toFloat()
    fun Y(p: Double) = top + padT + (1 - ((p.coerceIn(-0.15, 1.2)) + 0.1) / 1.25).toFloat() * ih
    // onda de la pista de fondo
    if (o.bg != null && o.bgSec != null) {
        val step = 2f * density; var x = 0f
        while (x < W) {
            val sec = o.bgSec.invoke(o.b0 + (x + step / 2) / W * (o.b1 - o.b0))
            if (sec != null) waveAt(o.bg, sec)?.let { waveCol(x, step, top + padT - 8 + (ih + 16) / 2, (ih + 16) * 0.48f, it, 0.2f) }
            x += step
        }
    }
    // grilla
    var b = ceil(o.b0)
    val label = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = C.text2)
    while (b <= o.b1) {
        val x = X(b); val bar = ((b.toInt() % 4) + 4) % 4 == 0
        drawLine(if (bar) C.tick else C.divider, Offset(x, top + padT - 6), Offset(x, top + H - padB + 4), 1f)
        if (o.grid && bar && b >= 0 && x >= 0f && x + 24 * density < W) drawText(tm, (b.toInt() / 4 + 1).toString(), Offset(x + 4 * density, top + 3 * density), label)
        b += 1.0
    }
    for (p in listOf(0.0, 1.0)) drawLine(C.divider, Offset(0f, Y(p)), Offset(W, Y(p)), 1f, pathEffect = dashGrid)
    // referencia: la curva humana, con su banda de tolerancia tenue
    if (o.strokes.isNotEmpty()) {
        val guide = o.guide ?: Human.ideal(o.strokes, 90.0)
        val step = (o.b1 - o.b0) / W * 2.5 * density
        val va = max(o.b0, o.strokes.first().b0); val vb = min(o.b1, o.strokes.last().b1)
        if (o.tolB > 0 && vb > va) {
            val tolPx = (o.tolB / (o.b1 - o.b0) * W).toFloat()
            drawPath(curvePath(guide, va, vb, step, ::X, ::Y), C.accentText, alpha = 0.10f, style = Stroke(2 * tolPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val past = { s: Timed -> o.now != null && s.b1 < o.now }
        drawGuide(o.strokes, guide, o.b0, o.b1, step, ::X, ::Y, 4f * density, 1.5f * density, dash,
            { s -> s.g?.let { C.grade[it.grade] } ?: if (s.demo) C.deck2 else C.accentText },
            { s -> if (past(s) && s.g == null) 0.35f else if (past(s)) 0.55f else 1f },
            { s -> if (past(s)) 0.5f else 0.9f })
    }
    // trazo del usuario
    val sm = o.samples
    if (sm != null && sm.n > 1) {
        var i = max(0, sm.idxAt(o.b0) - 1)
        var pb = sm.b[i]; var pp = sm.p[i].toDouble(); var pf = sm.f[i]
        i++
        while (i < sm.n) {
            val nb = sm.b[i]
            if (nb > o.b1) break
            val np = sm.p[i].toDouble()
            if (nb - pb < 0.75) {
                if (pf.toInt() == 1) drawLine(C.text, Offset(X(pb), Y(pp)), Offset(X(nb), Y(np)), 2.5f * density, StrokeCap.Round)
                else drawLine(C.text, Offset(X(pb), Y(pp)), Offset(X(nb), Y(np)), 1f * density, alpha = 0.22f)
            }
            pb = nb; pp = np; pf = sm.f[i]; i++
        }
    }
    // ahora
    if (o.now != null) { val x = X(o.now); drawLine(Color.White, Offset(x, top + padT - 10), Offset(x, top + H - padB + 6), 2f * density) }
    // calificaciones
    o.flash?.let { list ->
        val t = System.currentTimeMillis()
        for (f in list) {
            val age = (t - f.at) / 900f
            if (age > 1 || f.b < o.b0) continue
            val st = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = C.grade[f.grade].copy(alpha = 1 - age))
            val r = tm.measure(C.gradeName[f.grade], st)
            val tx = (X(f.b) - r.size.width / 2).coerceIn(0f, max(0f, W - r.size.width))
            val ty = (top + H - padB - r.size.height - age * 10 * density).coerceAtLeast(0f)
            if (tx + r.size.width <= W) drawText(r, topLeft = Offset(tx, ty))
        }
    }
}

/** Franja del sonido a scratchear: se mueve con el disco; el cue en ámbar. */
fun DrawScope.drawScratchStrip(tm: TextMeasurer, pk: Peaks, cue: Double, p: Double, open: Boolean, nowX: Float, h: Float, name: String) {
    val W = size.width
    drawRect(C.dark, Offset.Zero, Size(W, h))
    val pxs = 260f * density / 2.6f * 1.6f
    val cur = cue + p * UNIT
    val step = 2f * density; var x = 0f
    while (x < W) {
        waveAt(pk, cur + (x - nowX) / pxs)?.let { waveCol(x, step, h / 2 + 4 * density, h * 0.4f, it, if (open) 0.95f else 0.4f) }
        x += step
    }
    val xc = (nowX + (cue - cur) * pxs).toFloat()
    drawRect(C.cue, Offset(xc - density, 0f), Size(2 * density, h))
    drawRect(Color.White, Offset(nowX - density, 0f), Size(2 * density, h))
    if (size.width > 120 * density && size.height > 16 * density) drawText(tm, "SONIDO · " + name.uppercase(), Offset(8 * density, 4 * density), TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = C.dim), maxLines = 1, size = androidx.compose.ui.geometry.Size(size.width - 16 * density, 14 * density))
    drawLine(C.divider, Offset(0f, h - 1), Offset(W, h - 1), 1f)
}

fun DrawScope.drawDisc(p: Double, open: Boolean) {
    val s = min(size.width, size.height); val r = s / 2; val c = Offset(size.width / 2, size.height / 2)
    drawCircle(C.dark, r - 2, c)
    var k = r * 0.42f
    while (k < r - 10) { drawCircle(C.divider, k, c, style = Stroke(1f)); k += 5 * density }
    val ang = (p * UNIT / REV * 360).toFloat()
    rotate(ang, c) {
        drawCircle(C.accent, r * 0.36f, c)
        drawCircle(Color.White, r * 0.24f, c, style = Stroke(max(2f, r * 0.03f)))
        drawCircle(Color.White, r * 0.04f, c)
        drawLine(if (open) Color.White else C.dim, Offset(c.x, c.y - r * 0.44f), Offset(c.x, c.y - r + 12 * density), 4 * density, StrokeCap.Round)
    }
    // anillo: distancia al cue
    val off = (p * UNIT / REV).coerceIn(-1.0, 1.0)
    val ringR = r - 4 * density
    drawCircle(C.divider, ringR, c, style = Stroke(5 * density))
    drawArc(if (open) C.accentText else C.tick, -90f, (off * 360).toFloat(), false, Offset(c.x - ringR, c.y - ringR), Size(ringR * 2, ringR * 2), style = Stroke(5 * density, cap = StrokeCap.Round))
    drawCircle(C.cue, 4 * density, Offset(c.x, c.y - ringR))
    val a = (-PI / 2 + off * 2 * PI)
    drawCircle(Color.White, 4.5f * density, Offset(c.x + (cos(a) * ringR).toFloat(), c.y + (sin(a) * ringR).toFloat()))
}

/**
 * Medio vinilo pegado al borde: el centro queda fuera de pantalla y el radio es grande.
 * base: ángulo (grados) donde está la marca del cue con el disco en el cue (0 = derecha, 180 = izquierda).
 */
fun DrawScope.drawHalfDisc(p: Double, open: Boolean, c: Offset, r: Float, base: Float) {
    drawCircle(C.dark, r, c)
    var k = r * 0.4f
    while (k < r - 6 * density) { drawCircle(C.divider, k, c, style = Stroke(1f)); k += 6 * density }
    drawCircle(C.border, r - 1.5f * density, c, style = Stroke(3 * density))
    val ang = (p * UNIT / REV * 360).toFloat()
    rotate(base + ang, c) {
        drawCircle(C.accent, r * 0.36f, c)
        drawCircle(Color.White, r * 0.24f, c, style = Stroke(max(2f, r * 0.02f)))
        // marcas cada 30° para ver el giro; la del cue, gruesa
        for (i in 0 until 12) {
            val a = i * PI / 6
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r0 = if (i == 0) r * 0.40f else r * 0.78f
            val col = if (i == 0) (if (open) Color.White else C.dim) else C.tick
            drawLine(col, Offset(c.x + ca * r0, c.y + sa * r0), Offset(c.x + ca * (r - 10 * density), c.y + sa * (r - 10 * density)), (if (i == 0) 5f else 2f) * density, StrokeCap.Round)
        }
    }
    // anillo: distancia al cue, en el borde visible
    val off = (p * UNIT / REV).coerceIn(-1.0, 1.0)
    val ringR = r - 4 * density
    drawArc(if (open) C.accentText else C.tick, base, (off * 360).toFloat(), false, Offset(c.x - ringR, c.y - ringR), Size(ringR * 2, ringR * 2), style = Stroke(5 * density, cap = StrokeCap.Round))
    val cr = (base / 180f * PI)
    drawCircle(C.cue, 4.5f * density, Offset(c.x + (cos(cr) * ringR).toFloat(), c.y + (sin(cr) * ringR).toFloat()))
}

private val miniDash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
private val miniCache = HashMap<String, Pair<List<Timed>, Curve>>()

private fun miniGuide(t: Tech, cycles: Int) = miniCache.getOrPut(t.id + ":" + cycles) {
    val list = ArrayList<Timed>(); var start = 0.0
    repeat(cycles) { list += Techniques.timeStrokes(t.strokes, start); start += t.beats }
    list to Human.ideal(list, t.bpm.toDouble())
}

/** Mini gráfico de una técnica (tarjetas y detalle). */
fun DrawScope.drawMiniTtm(t: Tech, cycles: Int = if (t.beats >= 8) 1 else 2) {
    val beats = t.beats * cycles
    val W = size.width; val H = size.height; val pad = 6 * density
    fun X(b: Double) = (b / beats * W).toFloat()
    fun Y(p: Double) = (pad + (1 - p) * (H - 2 * pad)).toFloat()
    for (b in 0..beats) drawLine(if (b % 4 == 0) C.tick else C.divider, Offset(X(b.toDouble()), 0f), Offset(X(b.toDouble()), H), 1f)
    val (timed, guide) = miniGuide(t, cycles)
    drawGuide(timed, guide, 0.0, beats.toDouble(), beats / W.toDouble() * 2 * density, ::X, ::Y, 2.2f * density, 1.2f * density,
        miniDash, { C.accentText }, { 1f }, { 1f })
}

fun DrawScope.marker(x: Float, color: Color) {
    drawRect(color, Offset(x - density, 0f), Size(2 * density, size.height))
    drawCircle(color, 5 * density, Offset(x, 0f))
}

fun DrawScope.drawLogo() {
    val s = size.minDimension; val k = s / 108f; val c = Offset(size.width / 2, size.height / 2)
    drawCircle(C.accent, 44 * k, c)
    drawCircle(Color.White, 30 * k, c, style = Stroke(6 * k))
    drawCircle(Color.White.copy(alpha = 0.45f), 17 * k, c, style = Stroke(3 * k))
    drawCircle(Color.White, 4.5f * k, c)
    val a = Offset(c.x + 20 * k, c.y - 29 * k); val b = Offset(c.x - 18 * k, c.y + 31 * k)
    drawLine(C.accent, a, b, 11 * k, StrokeCap.Round)
    drawLine(Color.White, a, b, 4.5f * k, StrokeCap.Round)
}

fun DrawScope.drawTabIcon(kind: String, col: Color) {
    val w = size.width; val st = Stroke(2 * density, cap = StrokeCap.Round)
    val k = w / 24f
    when (kind) {
        "hoy" -> {
            drawCircle(col, 9 * k, center, style = st); drawCircle(col, 4 * k, center, style = st)
            drawLine(col, Offset(17 * k, 3.5f * k), Offset(7 * k, 20.5f * k), 2 * density, StrokeCap.Round)
        }
        "tecnicas" -> {
            drawLine(col, Offset(4 * k, 6 * k), Offset(20 * k, 6 * k), 2 * density, StrokeCap.Round)
            drawLine(col, Offset(4 * k, 12 * k), Offset(20 * k, 12 * k), 2 * density, StrokeCap.Round)
            drawLine(col, Offset(4 * k, 18 * k), Offset(14 * k, 18 * k), 2 * density, StrokeCap.Round)
        }
        "progreso" -> {
            drawLine(col, Offset(5 * k, 20 * k), Offset(5 * k, 11 * k), 2 * density, StrokeCap.Round)
            drawLine(col, Offset(12 * k, 20 * k), Offset(12 * k, 4 * k), 2 * density, StrokeCap.Round)
            drawLine(col, Offset(19 * k, 20 * k), Offset(19 * k, 14 * k), 2 * density, StrokeCap.Round)
        }
        else -> {
            drawCircle(col, 3 * k, center, style = st)
            for (i in 0 until 8) {
                val a = i * PI / 4; val r0 = 6.5f * k; val r1 = 9.5f * k
                drawLine(col, Offset(center.x + (cos(a) * r0).toFloat(), center.y + (sin(a) * r0).toFloat()), Offset(center.x + (cos(a) * r1).toFloat(), center.y + (sin(a) * r1).toFloat()), 2 * density, StrokeCap.Round)
            }
        }
    }
}
