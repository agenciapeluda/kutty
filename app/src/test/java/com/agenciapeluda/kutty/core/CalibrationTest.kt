package com.agenciapeluda.kutty.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    @Test
    fun puntuacionAceptaHumanosYRechazaErrores() {
        val r = Calibration.check()
        r.lines.forEach { println(it) }
        r.fails.take(40).forEach { println("FALLA: $it") }
        assertTrue("Calibración: ${r.fails.size} umbrales fallados. Primero: ${r.fails.firstOrNull()}", r.fails.isEmpty())
    }
}
