@file:OptIn(ExperimentalTextApi::class)

package com.agenciapeluda.kutty.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.agenciapeluda.kutty.R

/** Paleta de Cutlab: grises azulados, índigo de acento, verde, ámbar y rojo. */
object C {
    val bg = Color(0xFF07060F)
    val panel = Color(0xFF0E0D1A)
    val dark = Color(0xFF0B0A16)
    val raised = Color(0xFF17162A)
    val divider = Color(0xFF1D1B33)
    val pressed = Color(0xFF2A2650)
    val border = Color(0xFF2A2940)
    val tick = Color(0xFF3A3858)
    val text = Color(0xFFF4F5FB)
    val text2 = Color(0xFF9CA1B8)
    val dim = Color(0xFF6E7390)
    val accent = Color(0xFF635BFF)
    val accentText = Color(0xFF8B85FF)
    val accentDark = Color(0xFF2B2766)
    val ok = Color(0xFF2BD67B)
    val cue = Color(0xFFFFB547)
    val danger = Color(0xFFFF4D5E)
    val deck2 = Color(0xFF00C2D8)
    val waveLow = Color(0xFFFF3366)
    val waveMid = Color(0xFF2EE88F)
    val waveHigh = Color(0xFF45B8FF)
    val grade = listOf(ok, accentText, cue, danger)
    val gradeName = listOf("Perfecto", "Bien", "Flojo", "Fallo")
    fun acc(a: Int) = if (a >= 85) ok else if (a >= 65) accentText else if (a >= 40) cue else danger
}

private fun inter(w: Int) = Font(R.font.inter_tight, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private fun mono(w: Int) = Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))
val Mono = FontFamily(mono(500), mono(600))

object T {
    val h1 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = C.text)
    val h2 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = C.text2, letterSpacing = 0.8.sp)
    val body = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = C.text)
    val bodyB = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = C.text)
    val small = TextStyle(fontFamily = Inter, fontSize = 12.5.sp, color = C.text2)
    val tiny = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = C.dim)
    val num = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = C.text)
    val monoS = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = C.text2)
}

@Composable
fun KuttyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.White, background = C.bg, surface = C.panel, onSurface = C.text,
            onBackground = C.text, surfaceVariant = C.raised, outline = C.border, secondary = C.accentText,
        ),
        content = content,
    )
}
