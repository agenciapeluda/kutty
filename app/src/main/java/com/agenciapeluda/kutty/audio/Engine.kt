package com.agenciapeluda.kutty.audio

/** Puente al motor nativo (app/src/main/cpp/engine.cpp). */
object Engine {
    init { System.loadLibrary("kutty") }

    var sampleRate = 48000
        private set
    var started = false
        private set

    fun start(outDevice: Int = 0, channels: Int = 2): Int {
        if (!started) { sampleRate = nStart(outDevice, channels); started = true }
        return sampleRate
    }

    private val lock = Any()
    fun setTarget(posSec: Double, velSec: Double) = synchronized(lock) { nSetTarget(posSec, velSec) }
    /** [tNs]: momento real del evento, en el reloj de System.nanoTime (CLOCK_MONOTONIC). */
    fun setTarget(posSec: Double, velSec: Double, tNs: Long) = synchronized(lock) { nSetTargetAt(posSec, velSec, tNs) }
    fun setFader(open: Boolean) = nSetFader(if (open) 1f else 0f)

    external fun nStart(deviceId: Int, channels: Int): Int
    external fun nSetOutput(deviceId: Int, channels: Int)
    external fun nOutInfo(out: IntArray)
    external fun nSetKeylock(on: Boolean)
    external fun nSetSample(data: FloatArray)
    external fun nSetBeat(interleaved: FloatArray)
    external fun nSetTarget(t: Double, v: Double)
    external fun nSetTargetAt(t: Double, v: Double, tNs: Long)
    external fun nPushPoints(ts: LongArray, pos: DoubleArray, n: Int, vel: Double)
    /** Todos los puntos de una tanda (posición en segundos de sonido, tiempo real en ns). */
    fun pushPoints(ts: LongArray, pos: DoubleArray, n: Int, vel: Double) = synchronized(lock) { nPushPoints(ts, pos, n, vel) }
    external fun nSetEnd(end: Double)
    /** Demo y mano automática: el motor toca la trayectoria programada (disco y/o fader) en vez de la del usuario. */
    external fun nScript(disc: Boolean, fader: Boolean)
    /** Puntos programados por cuadro de audio: posición en segundos de sonido y fader (1 abierto). */
    external fun nPushScript(frames: LongArray, pos: DoubleArray, fader: ByteArray, n: Int)
    external fun nRecStart(seconds: Double): Int
    external fun nRecProgress(): Float
    external fun nRecFetch(out: FloatArray): Int
    external fun nSetFader(v: Float)
    external fun nSetSources(disc: Int, fader: Int)
    external fun nSetCue(cue: Double)
    external fun nSetVolumes(beat: Float, scratch: Float)
    external fun nStartBeat(frame: Long, startSec: Double, rate: Double, loop: Boolean, loopLen: Double)
    external fun nStopBeat()
    /** Libre: pausa y reanuda el beat en un cuadro; al reanudar sigue desde donde quedó. */
    external fun nPauseBeat(frame: Long)
    /** Arranca el beat en posSec con el loop desde loopStart (Libre: mover el inicio sin cortar la pista). */
    external fun nStartBeatAt(frame: Long, posSec: Double, loopStart: Double, rate: Double, loop: Boolean, loopLen: Double)
    external fun nResumeBeat(frame: Long)
    external fun nRate(frame: Long, rate: Double)
    external fun nClick(frame: Long, accent: Boolean)
    external fun nPreview(start: Double, dur: Double)
    external fun nStopPreview()
    external fun nJump(pos: Double)
    external fun nWritten(): Long
    external fun nHeard(): Double
    external fun nLatency(): Double
    external fun nDrain(frames: LongArray, pos: FloatArray, f: ByteArray): Int
    external fun nOpenInput(deviceId: Int): Boolean
    external fun nDvsConfig(threshold: Float, carrier: Float, invert: Boolean)
    external fun nDvsRecenter()
    external fun nDvsState(out: FloatArray)
}
