package com.agenciapeluda.kutty

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Si la app se cierra por un error, guarda el detalle y lo muestra al volver a abrirla. */
object CrashLog {
    var report by mutableStateOf<String?>(null)
    private var file: File? = null

    fun install(ctx: Context) {
        val f = File(ctx.filesDir, "ultimo_cierre.txt")
        file = f
        if (f.exists()) report = runCatching { f.readText() }.getOrNull()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                f.writeText("Kutty ${BuildConfig.VERSION_NAME} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\nHilo: ${t.name}\n\n" + e.stackTraceToString().take(6000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun dismiss() { report = null; runCatching { file?.delete() } }
}
