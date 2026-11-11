package com.agenciapeluda.kutty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agenciapeluda.kutty.audio.Engine
import com.agenciapeluda.kutty.audio.Inputs
import com.agenciapeluda.kutty.core.Session
import com.agenciapeluda.kutty.data.Store
import com.agenciapeluda.kutty.ui.App
import com.agenciapeluda.kutty.ui.Perms
import com.agenciapeluda.kutty.ui.applyAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Inputs.hasMicPermission = ok
        Inputs.reopenDvs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferHighRefresh()
        CrashLog.install(this)
        com.agenciapeluda.kutty.ui.Recorder.ctx = applicationContext
        Store.init(this)
        Inputs.preferredOutput(this).let { (id, ch) -> Engine.start(id, ch) }
        Engine.nSetVolumes(Store.set.beatVol, Store.set.scratchVol)
        Inputs.hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Perms.requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        Inputs.initMidi(this)
        Inputs.initAudioInputs(this)
        Inputs.reopenDvs()
        if (!Store.audioReady) lifecycleScope.launch {
            withContext(Dispatchers.Default) { Store.loadAudio() }
            applyAudio()
            Store.audioReady = true
        }
        setContent { App() }
    }

    /** 120 Hz (o lo máximo que dé la pantalla) mientras la app está abierta. */
    private fun preferHighRefresh() {
        val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        d ?: return
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    override fun onStop() {
        super.onStop()
        Session.stop(true)
    }
}
