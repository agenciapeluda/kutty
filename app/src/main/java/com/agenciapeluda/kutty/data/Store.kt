package com.agenciapeluda.kutty.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agenciapeluda.kutty.audio.AudioLib
import com.agenciapeluda.kutty.audio.BeatItem
import com.agenciapeluda.kutty.audio.Engine
import com.agenciapeluda.kutty.audio.SoundItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SessionRec(
    val t: Long, val id: String, val mode: String, val bpm: Int, val acc: Int, val pts: Int, val dur: Int,
    val counts: IntArray, val combo: Int, val aborted: Boolean,
)

/** Ajustes: Compose los observa, así la interfaz se actualiza sola. */
class Settings {
    var beat by mutableStateOf("boombap")
    var sound by mutableStateOf("ahh")
    var beatVol by mutableStateOf(0.75f)
    var scratchVol by mutableStateOf(0.9f)
    var metro by mutableStateOf(false)
    var sens by mutableStateOf(1f)
    var motor by mutableStateOf(false)
    var platter by mutableStateOf("circular")
    var faderMode by mutableStateOf("slide")
    var cutIn by mutableStateOf(0.08f)
    var hamster by mutableStateOf(false)
    var diff by mutableStateOf("normal")
    var bars by mutableIntStateOf(4)
    var waves by mutableStateOf(true)
    var calib by mutableIntStateOf(0)
    // fuentes: auto, dvs, midi, touch
    var discSrc by mutableStateOf("auto")
    var faderSrc by mutableStateOf("auto")
    var dvsDevice by mutableIntStateOf(0)      // 0 = ninguna
    var keylock by mutableStateOf(false)
    var progressive by mutableStateOf(false)
    var progMax by mutableIntStateOf(110)
    var progStep by mutableIntStateOf(2)
    var outDevice by mutableIntStateOf(0)      // 0 = auto (USB si hay), -1 = teléfono, otro = id
    var dvsThreshold by mutableStateOf(0.02f)
    var dvsInvert by mutableStateOf(false)
    var jogSteps by mutableIntStateOf(720)
    var xfaderCutLag by mutableStateOf(0.03f)
    var xfaderSide by mutableStateOf("right")   // qué lado del crossfader abre el sonido
    var learnJog by mutableStateOf("")          // "status:data1" aprendido
    var learnFader by mutableStateOf("")

    fun toJson() = JSONObject().apply {
        put("beat", beat); put("sound", sound); put("beatVol", beatVol.toDouble()); put("scratchVol", scratchVol.toDouble())
        put("metro", metro); put("sens", sens.toDouble()); put("motor", motor); put("platter", platter); put("faderMode", faderMode)
        put("cutIn", cutIn.toDouble()); put("hamster", hamster); put("diff", diff); put("bars", bars); put("waves", waves); put("calib", calib)
        put("discSrc", discSrc); put("faderSrc", faderSrc); put("dvsDevice", dvsDevice); put("outDevice", outDevice); put("keylock", keylock); put("progressive", progressive); put("progMax", progMax); put("progStep", progStep); put("dvsThreshold", dvsThreshold.toDouble())
        put("dvsInvert", dvsInvert); put("jogSteps", jogSteps); put("xfaderCutLag", xfaderCutLag.toDouble()); put("xfaderSide", xfaderSide)
        put("learnJog", learnJog); put("learnFader", learnFader)
    }
    fun fromJson(o: JSONObject) {
        beat = o.optString("beat", beat); sound = o.optString("sound", sound)
        beatVol = o.optDouble("beatVol", beatVol.toDouble()).toFloat(); scratchVol = o.optDouble("scratchVol", scratchVol.toDouble()).toFloat()
        metro = o.optBoolean("metro", metro); sens = o.optDouble("sens", sens.toDouble()).toFloat(); motor = o.optBoolean("motor", motor)
        platter = o.optString("platter", platter); faderMode = o.optString("faderMode", faderMode)
        cutIn = o.optDouble("cutIn", cutIn.toDouble()).toFloat(); hamster = o.optBoolean("hamster", hamster); diff = o.optString("diff", diff)
        bars = o.optInt("bars", bars); waves = o.optBoolean("waves", waves); calib = o.optInt("calib", calib)
        discSrc = o.optString("discSrc", discSrc); faderSrc = o.optString("faderSrc", faderSrc); dvsDevice = o.optInt("dvsDevice", dvsDevice); outDevice = o.optInt("outDevice", outDevice); keylock = o.optBoolean("keylock", keylock); progressive = o.optBoolean("progressive", progressive); progMax = o.optInt("progMax", progMax); progStep = o.optInt("progStep", progStep)
        dvsThreshold = o.optDouble("dvsThreshold", dvsThreshold.toDouble()).toFloat(); dvsInvert = o.optBoolean("dvsInvert", dvsInvert)
        jogSteps = o.optInt("jogSteps", jogSteps); xfaderCutLag = o.optDouble("xfaderCutLag", xfaderCutLag.toDouble()).toFloat()
        xfaderSide = o.optString("xfaderSide", xfaderSide); learnJog = o.optString("learnJog", learnJog); learnFader = o.optString("learnFader", learnFader)
    }
}

object Store {
    lateinit var dir: File
    val set = Settings()
    val sessions = mutableStateListOf<SessionRec>()
    val best = mutableStateMapOf<String, Int>()
    val sounds = mutableStateMapOf<String, SoundItem>()
    val beats = mutableStateMapOf<String, BeatItem>()
    var audioReady by mutableStateOf(false)

    fun init(ctx: Context) {
        dir = ctx.filesDir
        val f = File(dir, "kutty.json")
        if (f.exists()) runCatching {
            val o = JSONObject(f.readText())
            o.optJSONObject("settings")?.let { set.fromJson(it) }
            o.optJSONArray("sessions")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i); val c = s.getJSONArray("counts")
                    sessions.add(SessionRec(s.getLong("t"), s.getString("id"), s.getString("mode"), s.getInt("bpm"), s.getInt("acc"), s.getInt("pts"), s.getInt("dur"),
                        IntArray(4) { c.getInt(it) }, s.optInt("combo"), s.optBoolean("aborted")))
                }
            }
            o.optJSONObject("best")?.let { b -> b.keys().forEach { best[it] = b.getInt(it) } }
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("settings", set.toJson())
        o.put("sessions", JSONArray().apply {
            sessions.forEach { s ->
                put(JSONObject().apply {
                    put("t", s.t); put("id", s.id); put("mode", s.mode); put("bpm", s.bpm); put("acc", s.acc); put("pts", s.pts); put("dur", s.dur)
                    put("counts", JSONArray(s.counts.toList())); put("combo", s.combo); put("aborted", s.aborted)
                })
            }
        })
        o.put("best", JSONObject().apply { best.forEach { (k, v) -> put(k, v) } })
        runCatching { val tmp = File(dir, "kutty.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(File(dir, "kutty.json")) }
    }

    fun resetProgress() { sessions.clear(); best.clear(); save() }

    fun curSound(): SoundItem? = sounds[set.sound] ?: sounds["ahh"]
    fun curBeat(): BeatItem? = beats[set.beat] ?: beats["boombap"]

    // ---------- audio ----------
    private val audioDir get() = File(dir, "audio").apply { mkdirs() }
    private val indexFile get() = File(audioDir, "index.json")

    /** Arma los sonidos y beats de fábrica y carga los del usuario. Corre fuera del hilo principal. */
    fun loadAudio() {
        val sr = Engine.sampleRate
        val s = HashMap<String, SoundItem>(); val b = LinkedHashMap<String, BeatItem>()
        for ((k, name) in listOf("ahh" to "Ahh", "fresh" to "Fresh", "tono" to "Tono")) {
            val d = AudioLib.synthSound(k, sr)
            s[k] = SoundItem(k, name, d, sr, AudioLib.autoCue(d, sr), AudioLib.peaks(d, 1, sr), true)
        }
        for ((k, def) in AudioLib.beatDefs) {
            val d = AudioLib.synthBeat(def, sr)
            b[k] = BeatItem(k, def.name, d, sr, def.bpm, 0.0, true, 2, AudioLib.peaks(d, 2, sr), true)
        }
        val idx = readIndex()
        for (i in 0 until idx.length()) runCatching {
            val m = idx.getJSONObject(i)
            val file = File(audioDir, m.getString("file"))
            if (!file.exists()) return@runCatching
            val key = m.getString("key"); val name = m.getString("name")
            if (m.getString("kind") == "sound") {
                val d = AudioLib.normalize(AudioLib.convert(AudioLib.decode(file.path, 30.0), 1, sr, 30.0))
                s[key] = SoundItem(key, name, d, sr, m.optDouble("cue", AudioLib.autoCue(d, sr)), AudioLib.peaks(d, 1, sr), false)
            } else {
                val d = AudioLib.convert(AudioLib.decode(file.path, 600.0), 2, sr, 600.0)
                b[key] = BeatItem(key, name, d, sr, m.getDouble("bpm"), m.getDouble("start"), m.getBoolean("loop"), m.getInt("bars"), AudioLib.peaks(d, 2, sr), false)
            }
        }
        sounds.putAll(s); beats.putAll(b)
    }

    private fun readIndex(): JSONArray = runCatching { JSONArray(indexFile.readText()) }.getOrElse { JSONArray() }
    private fun writeIndex(a: JSONArray) { indexFile.writeText(a.toString()) }

    /** Copia el archivo elegido, lo decodifica y lo agrega. Devuelve la clave. */
    fun importAudio(ctx: Context, uri: Uri, kind: String): String {
        val sr = Engine.sampleRate
        val name = queryName(ctx, uri).substringBeforeLast('.').take(24).ifBlank { "Audio" }
        val key = kind + "-" + System.currentTimeMillis()
        val file = File(audioDir, key)
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> file.outputStream().use { inp.copyTo(it) } }
        val meta = JSONObject().put("key", key).put("name", name).put("kind", kind).put("file", key)
        if (kind == "sound") {
            val d = AudioLib.normalize(AudioLib.convert(AudioLib.decode(file.path, 30.0), 1, sr, 30.0))
            val it = SoundItem(key, name, d, sr, AudioLib.autoCue(d, sr), AudioLib.peaks(d, 1, sr), false)
            sounds[key] = it; meta.put("cue", it.cue)
        } else {
            val d = AudioLib.convert(AudioLib.decode(file.path, 600.0), 2, sr, 600.0)
            val pk = AudioLib.peaks(d, 2, sr)
            val it = BeatItem(key, name, d, sr, AudioLib.detectBpm(pk), AudioLib.autoDownbeat(pk), d.size / 2.0 / sr < 45, 4, pk, false)
            beats[key] = it
            meta.put("bpm", it.bpm).put("start", it.start).put("loop", it.loop).put("bars", it.bars)
        }
        val idx = readIndex(); idx.put(meta); writeIndex(idx)
        return key
    }

    fun saveMeta(key: String) {
        val idx = readIndex()
        for (i in 0 until idx.length()) {
            val m = idx.getJSONObject(i)
            if (m.getString("key") != key) continue
            sounds[key]?.let { m.put("cue", it.cue) }
            beats[key]?.let { m.put("bpm", it.bpm).put("start", it.start).put("loop", it.loop).put("bars", it.bars) }
        }
        writeIndex(idx)
    }

    fun deleteAudio(key: String) {
        val idx = readIndex(); val out = JSONArray()
        for (i in 0 until idx.length()) { val m = idx.getJSONObject(i); if (m.getString("key") == key) File(audioDir, m.getString("file")).delete() else out.put(m) }
        writeIndex(out)
        sounds.remove(key); beats.remove(key)
        if (set.sound == key) set.sound = "ahh"
        if (set.beat == key) set.beat = "boombap"
        save()
    }

    private fun queryName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "Audio"
        }
        return uri.lastPathSegment ?: "Audio"
    }
}
