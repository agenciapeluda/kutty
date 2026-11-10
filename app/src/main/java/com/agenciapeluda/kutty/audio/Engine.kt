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
    fun setFader(open: Boolean) = nSetFader(if (open) 1f else 0f)

    external fun nStart(deviceId: Int, channels: Int): Int
    external fun nSetOutput(deviceId: Int, channels: Int)
    external fun nOutInfo(out: IntArray)
    external fun nSetKeylock(on: Boolean)
    external fun nSetSample(data: FloatArray)
    external fun nSetBeat(interleaved: FloatArray)
    external fun nSetTarget(t: Double, v: Double)
    external fun nSetFader(v: Float)
    external fun nSetSources(disc: Int, fader: Int)
    external fun nSetCue(cue: Double)
    external fun nSetVolumes(beat: Float, scratch: Float)
    external fun nStartBeat(frame: Long, startSec: Double, rate: Double, loop: Boolean, loopLen: Double)
    external fun nStopBeat()
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
