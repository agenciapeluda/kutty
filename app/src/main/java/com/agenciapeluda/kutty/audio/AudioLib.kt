package com.agenciapeluda.kutty.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/** Onda por bandas: graves, medios y agudos (como las ondas RGB de Cutlab). */
class Peaks(val rate: Int, val low: FloatArray, val mid: FloatArray, val high: FloatArray, val max: Float) {
    val len get() = low.size
}

class SoundItem(val key: String, val name: String, val data: FloatArray, val sr: Int, var cue: Double, val peaks: Peaks, val builtin: Boolean) {
    val duration get() = data.size.toDouble() / sr
}

class BeatItem(
    val key: String, val name: String, val data: FloatArray, val sr: Int, var bpm: Double, var start: Double,
    var loop: Boolean, var bars: Int, val peaks: Peaks, val builtin: Boolean,
) {
    val duration get() = data.size / 2.0 / sr
    fun loopLen() = min(bars * 4 * 60.0 / bpm, duration - start)
    /** Segundo de la pista que suena en el tiempo b (la pista arranca en el tiempo -4). */
    fun secAt(b: Double): Double? {
        val tb = b + 4
        if (tb < 0) return null
        val s = tb * 60 / bpm
        if (loop) { val l = loopLen(); return if (l > 0) start + (s % l) else null }
        val t = start + s
        return if (t < duration) t else null
    }
}

class Biquad(type: String, f: Double, q: Double, sr: Int) {
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    init {
        val w = 2 * PI * f / sr; val al = sin(w) / (2 * q); val c = cos(w)
        val (nb0, nb1, nb2) = when (type) {
            "lp" -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)
            "hp" -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)
            else -> Triple(al, 0.0, -al)
        }
        val a0 = 1 + al
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0
    }
    fun p(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

object AudioLib {
    const val PEAK_RATE = 150

    fun normalize(x: FloatArray, to: Float = 0.9f): FloatArray {
        var m = 0f; for (v in x) m = max(m, abs(v))
        if (m <= 0f) return x
        val k = to / m
        for (i in x.indices) x[i] *= k
        return x
    }

    /** mono: un canal; stereo: intercalado L/R. */
    fun peaks(data: FloatArray, channels: Int, sr: Int): Peaks {
        val frames = data.size / channels
        val step = sr.toDouble() / PEAK_RATE
        val n = max(1, (frames / step).toInt() + 1)
        val lo = FloatArray(n); val mi = FloatArray(n); val hi = FloatArray(n)
        val fl = Biquad("lp", 220.0, 0.707, sr); val fm = Biquad("bp", 1200.0, 0.6, sr); val fh = Biquad("hp", 3500.0, 0.707, sr)
        for (i in 0 until frames) {
            val x = if (channels == 2) (data[i * 2] + data[i * 2 + 1]) * 0.5 else data[i].toDouble()
            val k = min(n - 1, (i / step).toInt())
            val a = abs(fl.p(x)).toFloat(); if (a > lo[k]) lo[k] = a
            val b = abs(fm.p(x)).toFloat(); if (b > mi[k]) mi[k] = b
            val c = abs(fh.p(x)).toFloat(); if (c > hi[k]) hi[k] = c
        }
        var mx = 1e-6f
        for (i in 0 until n) mx = max(mx, lo[i] + mi[i] + hi[i])
        return Peaks(PEAK_RATE, lo, mi, hi, mx)
    }

    fun autoCue(data: FloatArray, sr: Int): Double {
        var pk = 0f; for (v in data) pk = max(pk, abs(v))
        val th = pk * 0.12f
        for (i in data.indices) if (abs(data[i]) > th) return max(0.0, i.toDouble() / sr - 0.004)
        return 0.0
    }

    fun autoDownbeat(pk: Peaks): Double {
        var m = 0f; for (v in pk.low) m = max(m, v)
        for (i in 0 until pk.len) if (pk.low[i] > m * 0.4f) return max(0.0, (i - 1).toDouble() / pk.rate)
        return 0.0
    }

    /** BPM por autocorrelación de la envolvente de ataques, llevado a 70–140. */
    fun detectBpm(pk: Peaks): Double {
        val n = min(pk.len, pk.rate * 60)
        val on = FloatArray(n)
        for (i in 1 until n) {
            val a = pk.low[i] + 0.6f * pk.mid[i] + 0.3f * pk.high[i]
            val b = pk.low[i - 1] + 0.6f * pk.mid[i - 1] + 0.3f * pk.high[i - 1]
            on[i] = max(0f, a - b)
        }
        var best = 0.0; var bestBpm = 90.0; var bpm = 60.0
        while (bpm <= 180.0) {
            val lag = 60 / bpm * pk.rate
            var s = 0.0
            var i = (lag * 2).toInt() + 1
            while (i < n) {
                val x = i - lag; val k = x.toInt(); val fr = x - k
                if (k + 1 < n) s += on[i] * (on[k] * (1 - fr) + on[k + 1] * fr)
                i++
            }
            if (s > best) { best = s; bestBpm = bpm }
            bpm += 0.25
        }
        while (bestBpm >= 140) bestBpm /= 2
        while (bestBpm < 70) bestBpm *= 2
        return round(bestBpm * 2) / 2
    }

    // ---------- Decodificación ----------
    class Decoded(val data: FloatArray, val channels: Int, val sr: Int)

    fun decode(path: String, maxSeconds: Double): Decoded {
        val ex = MediaExtractor(); ex.setDataSource(path)
        var track = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
        }
        require(track >= 0 && fmt != null) { "No hay audio en el archivo" }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0); codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        val maxSamplesGuess = (maxSeconds * sr * ch).toLong()
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(5000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 5000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val bb = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                bb.position(info.offset); bb.limit(info.offset + info.size)
                if (floatPcm) { val fb = bb.asFloatBuffer(); while (fb.hasRemaining()) out.add(fb.get()) }
                else { val sb = bb.asShortBuffer(); while (sb.hasRemaining()) out.add(sb.get() / 32768f) }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                if (out.size > maxSeconds * sr * ch || out.size > maxSamplesGuess * 2) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return Decoded(out.toArray(), ch, sr)
    }

    /** A mono o estéreo intercalado, remuestreado a [sr]. */
    fun convert(d: Decoded, outCh: Int, sr: Int, maxSeconds: Double): FloatArray {
        val inFrames = d.data.size / d.channels
        val ratio = d.sr.toDouble() / sr
        val outFrames = min((inFrames / ratio).toInt(), (maxSeconds * sr).toInt())
        val o = FloatArray(outFrames * outCh)
        fun ch(i: Int, c: Int): Float = d.data[i * d.channels + min(c, d.channels - 1)]
        for (j in 0 until outFrames) {
            val x = j * ratio; val k = x.toInt(); val fr = (x - k).toFloat(); val k1 = min(k + 1, inFrames - 1)
            if (outCh == 1) {
                var a = 0f; var b = 0f
                for (c in 0 until d.channels) { a += d.data[k * d.channels + c]; b += d.data[k1 * d.channels + c] }
                a /= d.channels; b /= d.channels
                o[j] = a + (b - a) * fr
            } else {
                for (c in 0..1) { val a = ch(k, c); val b = ch(k1, c); o[j * 2 + c] = a + (b - a) * fr }
            }
        }
        return o
    }

    // ---------- Sonidos y beats sintéticos ----------
    private fun env(t: Double, pts: List<Pair<Double, Double>>): Double {
        var pt = 0.0; var pv = 0.0
        for ((et, ev) in pts) { if (t < et) return pv + (ev - pv) * (t - pt) / (et - pt); pt = et; pv = ev }
        return pv
    }

    fun synthSound(kind: String, sr: Int): FloatArray {
        val n = (1.6 * sr).toInt(); val o = FloatArray(n); val rnd = Random(7)
        when (kind) {
            "ahh" -> {
                val f1 = Biquad("bp", 780.0, 7.0, sr); val f2 = Biquad("bp", 1180.0, 9.0, sr); val f3 = Biquad("bp", 2850.0, 12.0, sr)
                var ph1 = 0.0; var ph2 = 0.0
                for (i in 0 until n) {
                    val t = i.toDouble() / sr; val f0 = 205 - 33 * min(1.0, t / 1.5)
                    ph1 = (ph1 + f0 / sr) % 1.0; ph2 = (ph2 + f0 * 1.004 / sr) % 1.0
                    val src = (2 * ph1 - 1) + (2 * ph2 - 1)
                    val v = f1.p(src) * 1.2 + f2.p(src) * 0.7 + f3.p(src) * 0.35
                    o[i] = (v * env(t, listOf(0.012 to 1.0, 1.2 to 0.85, 1.6 to 0.0))).toFloat()
                }
            }
            "fresh" -> {
                val nf = Biquad("bp", 4200.0, 1.2, sr); val sh = Biquad("bp", 2600.0, 1.5, sr)
                val f1 = Biquad("bp", 520.0, 8.0, sr); val f2 = Biquad("bp", 1750.0, 10.0, sr); val f3 = Biquad("bp", 2500.0, 12.0, sr)
                var ph = 0.0
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val nz = rnd.nextDouble() * 2 - 1
                    var v = nf.p(nz) * env(t, listOf(0.02 to 0.9, 0.2 to 0.7, 0.28 to 0.0))
                    val f0 = if (t < 0.25) 190.0 else 190 - 30 * min(1.0, (t - 0.25) / 0.6)
                    ph = (ph + f0 / sr) % 1.0; val saw = 2 * ph - 1
                    v += (f1.p(saw) * 1.2 + f2.p(saw) * 0.6 + f3.p(saw) * 0.3) * env(t, listOf(0.26 to 0.0, 0.3 to 1.0, 0.78 to 0.8, 0.86 to 0.0))
                    v += sh.p(rnd.nextDouble() * 2 - 1) * env(t, listOf(0.8 to 0.0, 0.86 to 0.9, 1.2 to 0.6, 1.4 to 0.0))
                    o[i] = v.toFloat()
                }
            }
            else -> {
                val lp = Biquad("lp", 3200.0, 0.707, sr); var ph = 0.0
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    ph = (ph + 330.0 / sr) % 1.0
                    o[i] = (lp.p(2 * ph - 1) * env(t, listOf(0.006 to 0.8, 1.4 to 0.7, 1.6 to 0.0))).toFloat()
                }
            }
        }
        return normalize(o)
    }

    class BeatDef(val name: String, val bpm: Double, val swing: Double, val k: IntArray, val s: IntArray, val hats: Int, val ghost: IntArray, val chords: Boolean)
    val beatDefs = linkedMapOf(
        "boombap" to BeatDef("Boom bap", 88.0, 0.0, intArrayOf(0, 6, 10, 16, 22, 25), intArrayOf(4, 12, 20, 28), 2, intArrayOf(15, 31), false),
        "lofi" to BeatDef("Lofi", 80.0, 0.12, intArrayOf(0, 7, 16, 19, 23), intArrayOf(4, 12, 20, 28), 2, intArrayOf(), true),
        "break" to BeatDef("Break", 94.0, 0.0, intArrayOf(0, 3, 10, 16, 19, 26), intArrayOf(4, 12, 14, 20, 28, 30), 1, intArrayOf(), false),
    )

    fun synthBeat(d: BeatDef, sr: Int): FloatArray {
        val len = 8 * 60 / d.bpm; val n = (len * sr).toInt(); val mono = FloatArray(n); val step = len / 32
        val rnd = Random(3)
        fun at(i: Int) = i * step + if (i % 4 == 2) d.swing * step * 2 else 0.0
        fun addAt(t0: Double, dur: Double, f: (Double) -> Double) {
            val s0 = (t0 * sr).toInt(); val m = (dur * sr).toInt()
            for (j in 0 until m) { val idx = s0 + j; if (idx in 0 until n) mono[idx] += f(j.toDouble() / sr).toFloat() }
        }
        for (i in d.k) { var ph = 0.0; addAt(at(i), 0.4) { t -> val f = 42 + 88 * exp(-t * 25); ph += f / sr; sin(2 * PI * ph) * 0.001.pow(t / 0.38) } }
        fun snare(t0: Double, a: Double) {
            val bp = Biquad("bp", 1900.0, 0.7, sr)
            addAt(t0, 0.22) { t -> bp.p(rnd.nextDouble() * 2 - 1) * a * 2.2 * 0.001.pow(t / 0.2) + sin(2 * PI * 190 * t) * a * 0.5 * 0.001.pow(t / 0.09) }
        }
        for (i in d.s) snare(at(i), if (d.chords) 0.5 else 0.7)
        for (i in d.ghost) snare(at(i), 0.18)
        var i = 0
        while (i < 32) {
            val hp = Biquad("hp", 7500.0, 0.707, sr); val a = if (i % 4 == 0) 0.2 else 0.13
            addAt(at(i), 0.05) { t -> hp.p(rnd.nextDouble() * 2 - 1) * a * 1.6 * 0.001.pow(t / 0.045) }
            i += d.hats
        }
        if (d.chords) {
            val chords = listOf(doubleArrayOf(220.0, 261.6, 329.6, 392.0), doubleArrayOf(146.8, 174.6, 220.0, 261.6))
            chords.forEachIndexed { bar, notes ->
                for (f in notes) addAt(bar * len / 2, len / 2) { t -> val tri = 2 * abs(2 * ((t * f) % 1.0) - 1) - 1; tri * env(t, listOf(0.05 to 0.05, len / 2 - 0.1 to 0.035, len / 2 to 0.0)) }
            }
            val hp = Biquad("hp", 3000.0, 0.707, sr)
            for (j in 0 until n) mono[j] += (hp.p(rnd.nextDouble() * 2 - 1) * 0.012).toFloat()
        }
        normalize(mono, 0.85f)
        val st = FloatArray(n * 2)
        for (j in 0 until n) { st[j * 2] = mono[j]; st[j * 2 + 1] = mono[j] }
        return st
    }

    /** Copia mono de un sonido como AudioBuffer intercalado para preescucha (no se usa en el motor). */
    fun floorDiv(a: Double, b: Double) = floor(a / b)
}

/** Lista de floats sin boxing. */
class FloatList(cap: Int = 1 shl 16) {
    private var a = FloatArray(cap)
    var size = 0
        private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray(): FloatArray = a.copyOf(size)
}
