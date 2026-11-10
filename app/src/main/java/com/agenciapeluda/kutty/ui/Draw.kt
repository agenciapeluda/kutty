package com.agenciapeluda.kutty.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import com.agenciapeluda.kutty.core.Flash
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
)

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
        if (o.grid && bar && b >= 0) drawText(tm, (b.toInt() / 4 + 1).toString(), Offset(x + 4 * density, top + 3 * density), label)
        b += 1.0
    }
    for (p in listOf(0.0, 1.0)) drawLine(C.divider, Offset(0f, Y(p)), Offset(W, Y(p)), 1f, pathEffect = dashGrid)
    // referencia
    for (s in o.strokes) {
        if (s.b1 < o.b0 || s.b0 > o.b1) continue
        val n = s.mask.length
        val g = s.g
        val col = if (g != null) C.grade[g.grade] else if (s.demo) C.deck2 else C.accentText
        val past = o.now != null && s.b1 < o.now
        for (k in 0 until n) {
            val xa = s.b0 + s.dur * k / n; val xb = s.b0 + s.dur * (k + 1) / n
            val pa = s.p0 + (s.p1 - s.p0) * k / n; val pb = s.p0 + (s.p1 - s.p0) * (k + 1) / n
            if (s.mask[k] == '1') drawLine(col, Offset(X(xa), Y(pa)), Offset(X(xb), Y(pb)), 4f * density, StrokeCap.Round, alpha = if (past && g == null) 0.35f else if (past) 0.55f else 1f)
            else drawLine(C.dim, Offset(X(xa), Y(pa)), Offset(X(xb), Y(pb)), 1.5f * density, pathEffect = dash, alpha = if (past) 0.5f else 0.9f)
        }
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
            drawText(r, topLeft = Offset(X(f.b) - r.size.width / 2, top + H - padB - r.size.height - age * 10 * density))
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
    drawText(tm, "SONIDO · " + name.uppercase(), Offset(8 * density, 4 * density), TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = C.dim))
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

/** Mini gráfico de una técnica (tarjetas y detalle). */
fun DrawScope.drawMiniTtm(t: Tech, cycles: Int = if (t.beats >= 8) 1 else 2) {
    val beats = t.beats * cycles
    val W = size.width; val H = size.height; val pad = 6 * density
    fun X(b: Double) = (b / beats * W).toFloat()
    fun Y(p: Double) = (pad + (1 - p) * (H - 2 * pad)).toFloat()
    for (b in 0..beats) drawLine(if (b % 4 == 0) C.tick else C.divider, Offset(X(b.toDouble()), 0f), Offset(X(b.toDouble()), H), 1f)
    var start = 0.0
    repeat(cycles) {
        for (s in Techniques.timeStrokes(t.strokes, start)) {
            val n = s.mask.length
            for (k in 0 until n) {
                val xa = s.b0 + s.dur * k / n; val xb = s.b0 + s.dur * (k + 1) / n
                val pa = s.p0 + (s.p1 - s.p0) * k / n; val pb = s.p0 + (s.p1 - s.p0) * (k + 1) / n
                if (s.mask[k] == '1') drawLine(C.accentText, Offset(X(xa), Y(pa)), Offset(X(xb), Y(pb)), 2.2f * density, StrokeCap.Round)
                else drawLine(C.dim, Offset(X(xa), Y(pa)), Offset(X(xb), Y(pb)), 1.2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
        }
        start += t.beats
    }
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
