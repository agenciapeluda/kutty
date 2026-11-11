package com.agenciapeluda.kutty.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agenciapeluda.kutty.audio.Engine
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Diagnóstico: graba 10 s de lo que sale por el motor y lo guarda como WAV en Descargas. */
object Recorder {
    const val SECONDS = 10.0
    lateinit var ctx: Context
    var recording by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    private var sr = 48000

    fun start() {
        if (recording) return
        val r = Engine.nRecStart(SECONDS)
        if (r <= 0) return
        sr = r; recording = true; progress = 0f
    }

    /** En cada cuadro de la práctica. */
    fun poll() {
        if (!recording) return
        val p = Engine.nRecProgress()
        if (p < 0f) { recording = false; return }
        progress = p
        if (p < 1f) return
        val data = FloatArray((SECONDS * sr).toInt() * 2)
        val n = Engine.nRecFetch(data)
        recording = false
        if (n <= 0) return
        val rate = sr
        thread(name = "kutty-wav") {
            val name = "kutty-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
            val msg = runCatching { save(name, data, n, rate) }.getOrElse { "No pude guardar la grabación" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { Nav.toast = msg; share() }
        }
    }

    private var lastUri: android.net.Uri? = null

    /** Abre el menú de compartir con la última grabación (WhatsApp, Drive, mail, el chat…). */
    fun share() {
        val uri = lastUri ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            ctx.startActivity(android.content.Intent.createChooser(send, "Mandar la grabación").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private fun save(name: String, data: FloatArray, n: Int, rate: Int): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kutty")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("sin destino")
            ctx.contentResolver.openOutputStream(uri)!!.use { writeWav(it, data, n, rate) }
            lastUri = uri
            return "Grabación guardada en Descargas/Kutty/$name"
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
        val f = File(dir, name)
        f.outputStream().use { writeWav(it, data, n, rate) }
        return "Grabación guardada en ${f.path}"
    }

    /** WAV PCM de 16 bits, estéreo. */
    private fun writeWav(out: OutputStream, data: FloatArray, n: Int, rate: Int) {
        val bytes = n * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + bytes); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(rate); h.putInt(rate * 4); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(bytes)
        out.write(h.array())
        val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort((data[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        out.write(b.array())
    }
}
