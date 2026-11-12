@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.agenciapeluda.kutty.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenciapeluda.kutty.CrashLog
import com.agenciapeluda.kutty.core.Session
import com.agenciapeluda.kutty.core.Tech
import com.agenciapeluda.kutty.core.Techniques
import com.agenciapeluda.kutty.data.SessionRec
import com.agenciapeluda.kutty.data.Store
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Nav {
    var tab by mutableStateOf("hoy")
    var practice by mutableStateOf(false)
    var bpmSheet by mutableStateOf(false)
    var detail by mutableStateOf<Tech?>(null)
    var chooser by mutableStateOf<String?>(null)
    var soundEditor by mutableStateOf<String?>(null)
    var trackEditor by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var libreEdit by mutableStateOf(false)
}

fun openPractice(t: Tech, mode: String, demo: Boolean = false) {
    Session.open(t, mode)
    Nav.libreEdit = false
    Nav.practice = true
    if (demo && Store.audioReady) Session.start(true)
}

fun dayKey(ts: Long): String { val c = Calendar.getInstance().apply { timeInMillis = ts }; return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}" }
fun streak(): Int {
    val days = Store.sessions.map { dayKey(it.t) }.toSet()
    val c = Calendar.getInstance(); var n = 0
    if (dayKey(c.timeInMillis) !in days) c.add(Calendar.DAY_OF_YEAR, -1)
    while (dayKey(c.timeInMillis) in days) { n++; c.add(Calendar.DAY_OF_YEAR, -1) }
    return n
}
fun mastered() = Techniques.all.count { (Store.best[it.id] ?: 0) >= 85 }
fun totalPts() = Store.sessions.sumOf { it.pts }
fun levelOf(pts: Int): Triple<Int, Int, Int> { var l = 1; var need = 1500; var acc = 0; while (pts >= acc + need) { acc += need; l++; need = (need * 1.35).toInt() }; return Triple(l, pts - acc, need) }
fun nextTech() = Techniques.all.sortedBy { it.level }.firstOrNull { (Store.best[it.id] ?: 0) < 85 } ?: Techniques.all[0]
fun modeName(m: String) = when (m) { "aprender" -> "Aprender"; "desafio" -> "Desafío"; "libre" -> "Libre"; else -> "Practicar" }

@Composable
fun App() {
    KuttyTheme {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            if (Nav.practice) PracticeScreen() else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                    when (Nav.tab) {
                        "hoy" -> HoyScreen()
                        "tecnicas" -> TecnicasScreen()
                        "progreso" -> ProgresoScreen()
                        else -> AjustesScreen()
                    }
                }
                BottomBar()
            }
            Nav.detail?.let { DetailSheet(it) }
            Nav.chooser?.let { ChooserDialog(it) }
            Nav.soundEditor?.let { SoundEditor(it) }
            Nav.trackEditor?.let { TrackEditor(it) }
            Toast()
            CrashLog.report?.let { CrashReport(it) }
        }
    }
}

@Composable
private fun BottomBar() {
    Row(Modifier.fillMaxWidth().background(C.panel).navigationBarsPadding()) {
        listOf("hoy" to "Hoy", "tecnicas" to "Técnicas", "progreso" to "Progreso", "ajustes" to "Ajustes").forEach { (k, l) ->
            val on = Nav.tab == k
            Column(Modifier.weight(1f).clickable { Nav.tab = k }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(22.dp)) { drawTabIcon(k, if (on) C.accentText else C.dim) }
                Spacer(Modifier.height(3.dp))
                Text(l, style = T.tiny.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (on) C.accentText else C.dim))
            }
        }
    }
}

@Composable
fun Logo(size: Int = 32) { Canvas(Modifier.size(size.dp)) { drawLogo() } }

@Composable
private fun HoyScreen() {
    val today = dayKey(System.currentTimeMillis())
    val mins = Store.sessions.filter { dayKey(it.t) == today }.sumOf { it.dur } / 60
    val (lv, into, need) = levelOf(totalPts())
    val nt = nextTech()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(); Spacer(Modifier.width(10.dp))
            Text("Kutty", style = T.h1.copy(fontSize = 20.sp, color = C.accentText))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Racha", "${streak()} d", modifier = Modifier.weight(1f))
            Stat("Hoy", "$mins min", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Nivel", "$lv", "$into / $need pts", Modifier.weight(1f))
            Stat("Dominadas", "${mastered()} / ${Techniques.all.size}", modifier = Modifier.weight(1f))
        }
        H2("Seguí con")
        TechCard(nt, big = true)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), onClick = { openPractice(nt, "libre") }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scratch libre", style = T.bodyB)
                    Text("Tu pista y tu sonido, sin ejercicio: la app reconoce tus trucos y al parar te da la lectura", style = T.small.copy(color = C.text2))
                }
                Text("›", style = T.h1.copy(fontSize = 22.sp, color = C.accentText))
            }
        }
        H2("Últimas sesiones")
        if (Store.sessions.isEmpty()) Card(Modifier.fillMaxWidth()) {
            Text("Todavía no practicaste. Elegí una técnica y arrancá: el gráfico te muestra lo que hacés en vivo.", style = T.body.copy(color = C.text2), modifier = Modifier.padding(16.dp))
        }
        Store.sessions.takeLast(6).reversed().forEach { SessionRow(it); Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun SessionRow(s: SessionRec) {
    val df = remember { SimpleDateFormat("d MMM", Locale("es", "AR")) }
    if (s.libre) {
        val x = remember(s.t) { s.extra?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scratch libre", style = T.bodyB, maxLines = 1)
                    Text("${x?.optInt("n") ?: 0} trucos" + (x?.optInt("clean", -1)?.takeIf { it >= 0 }?.let { " · $it% cortes limpios" } ?: "") + " · ${df.format(s.t)}", style = T.small, maxLines = 1)
                }
                Text("${x?.optDouble("cps", 0.0) ?: 0.0} c/s", style = T.num.copy(fontSize = 15.sp, color = C.accentText))
            }
        }
        return
    }
    val t = Techniques.byId[s.id] ?: return
    Card(Modifier.fillMaxWidth(), onClick = { Nav.detail = t }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = T.bodyB, maxLines = 1)
                Text("${modeName(s.mode)} · ${s.bpm} BPM · ${df.format(s.t)}", style = T.small)
            }
            Text("${s.acc}%", style = T.num.copy(fontSize = 16.sp, color = C.acc(s.acc)))
        }
    }
}

@Composable
fun TechCard(t: Tech, big: Boolean = false) {
    val best = Store.best[t.id]
    Card(Modifier.fillMaxWidth(), onClick = { Nav.detail = t }) {
        Column {
            Canvas(Modifier.fillMaxWidth().height(if (big) 86.dp else 58.dp).background(C.dark)) { drawMiniTtm(t) }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, style = T.bodyB.copy(fontSize = if (big) 18.sp else 14.sp), maxLines = 1, modifier = Modifier.weight(1f))
                    if ((best ?: 0) >= 85) Tag("✓", C.ok, C.ok.copy(alpha = 0.15f))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(t.family)
                    Text("Nv ${t.level}", style = T.monoS.copy(color = C.cue))
                    if (best != null) Text("$best%", style = T.monoS.copy(color = C.acc(best)))
                }
            }
        }
    }
}

private var fq by mutableStateOf("")
private var ffam by mutableStateOf("Todas")
private var flvl by mutableIntStateOf(0)

@Composable
private fun TecnicasScreen() {
    val fams = listOf("Todas") + Techniques.all.map { it.family }.distinct()
    val list = Techniques.all.filter { (ffam == "Todas" || it.family == ffam) && (flvl == 0 || it.level == flvl) && (fq.isBlank() || (it.name + " " + it.family).contains(fq.trim(), true)) }
    LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Técnicas", style = T.h1, modifier = Modifier.weight(1f))
                    Text("${list.size} / ${Techniques.all.size}", style = T.monoS.copy(color = C.dim))
                }
                Spacer(Modifier.height(14.dp))
                BasicTextField(fq, { fq = it }, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(C.accent),
                    modifier = Modifier.fillMaxWidth().clip(R12).background(C.panel).padding(14.dp),
                    decorationBox = { inner -> if (fq.isEmpty()) Text("Buscar técnica", style = T.body.copy(color = C.dim)); inner() })
                Spacer(Modifier.height(10.dp))
                ScrollRow { fams.forEach { f -> Chip(f, f == ffam) { ffam = f } } }
                Spacer(Modifier.height(8.dp))
                ScrollRow { (0..10).forEach { l -> Chip(if (l == 0) "Todo nivel" else "Nv $l", l == flvl, mono = true) { flvl = l } } }
            }
        }
        items(list, key = { it.id }) { TechCard(it) }
    }
}

@Composable
private fun DetailSheet(t: Tech) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val best = Store.best[t.id]
    ModalBottomSheet(onDismissRequest = { Nav.detail = null }, sheetState = state, containerColor = C.panel, scrimColor = C.bg.copy(alpha = 0.6f)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = T.h1, maxLines = 1, modifier = Modifier.weight(1f))
            CloseX { Nav.detail = null }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(t.family); Text("Nivel ${t.level}", style = T.monoS.copy(color = C.cue)); Text("${t.bpm} BPM", style = T.monoS); Text(t.rhythm, style = T.small)
            }
            Spacer(Modifier.height(16.dp))
            Canvas(Modifier.fillMaxWidth().height(150.dp).clip(R12).background(C.dark)) { drawMiniTtm(t) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(18.dp, 4.dp)) { drawLine(C.accentText, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3 * density) }
                Text("fader abierto", style = T.small)
                Canvas(Modifier.size(18.dp, 4.dp)) { drawLine(C.dim, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f))) }
                Text("cerrado", style = T.small)
            }
            Text("Arriba = disco hacia adelante", style = T.tiny, modifier = Modifier.padding(top = 4.dp))
            Text(t.desc, style = T.body.copy(fontSize = 15.5.sp), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            t.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = T.body.copy(color = C.text2), modifier = Modifier.padding(vertical = 3.dp)) }
            if (best != null) Text("Tu mejor precisión: $best%", style = T.small.copy(color = C.acc(best)), modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Escuchar", Modifier.weight(1f), "ghost") { Nav.detail = null; openPractice(t, "practicar", true) }
                Btn("Practicar", Modifier.weight(1f)) { Nav.detail = null; openPractice(t, if ((best ?: 0) >= 65) "practicar" else "aprender") }
            }
        }
    }
}

private var progView by mutableStateOf("diario")

@Composable
private fun ProgresoScreen() {
    val cal = Calendar.getInstance()
    data class B(val key: String, val label: String, var min: Double = 0.0, var pts: Int = 0, var acc: Int? = null, var n: Int = 0)
    val buckets = ArrayList<B>()
    val keyFn: (Long) -> String
    when (progView) {
        "diario" -> {
            for (i in 13 downTo 0) { val c = cal.clone() as Calendar; c.add(Calendar.DAY_OF_YEAR, -i); buckets += B(dayKey(c.timeInMillis), c.get(Calendar.DAY_OF_MONTH).toString()) }
            keyFn = { dayKey(it) }
        }
        "mensual" -> {
            val mf = SimpleDateFormat("MMM", Locale("es", "AR"))
            for (i in 11 downTo 0) { val c = cal.clone() as Calendar; c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, -i); buckets += B("${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}", mf.format(c.time).replace(".", "")) }
            keyFn = { val c = Calendar.getInstance().apply { timeInMillis = it }; "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}" }
        }
        else -> {
            for (i in 4 downTo 0) { val y = cal.get(Calendar.YEAR) - i; buckets += B("$y", "$y") }
            keyFn = { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.YEAR).toString() }
        }
    }
    for (b in buckets) {
        val ss = Store.sessions.filter { keyFn(it.t) == b.key }
        b.min = ss.sumOf { it.dur } / 60.0; b.pts = ss.sumOf { it.pts }; b.n = ss.size
        val graded = ss.filter { it.acc >= 0 }   // las sesiones libres no tienen puntaje
        b.acc = if (graded.isEmpty()) null else graded.sumOf { it.acc } / graded.size
    }
    val maxMin = maxOf(1.0, buckets.maxOf { it.min })
    val accs = buckets.mapNotNull { it.acc }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Progreso", style = T.h1)
        Spacer(Modifier.height(14.dp))
        Seg(listOf("diario" to "Diario", "mensual" to "Mensual", "anual" to "Anual"), progView) { progView = it }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Minutos", "${buckets.sumOf { it.min }.toInt()}", modifier = Modifier.weight(1f))
            Stat("Puntos", "${buckets.sumOf { it.pts }}", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Sesiones", "${buckets.sumOf { it.n }}", modifier = Modifier.weight(1f))
            Stat("Precisión", if (accs.isEmpty()) "—" else "${accs.sum() / accs.size}%", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth().height(220.dp)) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                buckets.forEach { b ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (b.min > 0) "${b.min.toInt()}" else "", style = T.monoS.copy(fontSize = 10.sp))
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(C.dark), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight((b.min / maxMin).toFloat().coerceIn(0f, 1f)).background(b.acc?.let { C.acc(it) } ?: C.accentDark))
                        }
                        Text(b.label, style = T.tiny.copy(fontSize = 10.5.sp, color = C.text2), maxLines = 1)
                    }
                }
            }
        }
        Text("Minutos practicados; el color de cada barra es la precisión promedio.", style = T.tiny, modifier = Modifier.padding(top = 6.dp))
        H2("Dominio por familia")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Techniques.all.map { it.family }.distinct().forEach { f ->
                    val ts = Techniques.all.filter { it.family == f }
                    val m = ts.count { (Store.best[it.id] ?: 0) >= 85 }; val tried = ts.count { Store.best[it.id] != null }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, style = T.small.copy(color = C.text), modifier = Modifier.width(110.dp))
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(8.dp)).background(C.dark)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(tried.toFloat() / ts.size).background(C.accentDark))
                            Box(Modifier.fillMaxHeight().fillMaxWidth(m.toFloat() / ts.size).background(C.ok))
                        }
                        Text("$m/${ts.size}", style = T.monoS, modifier = Modifier.width(48.dp).padding(start = 10.dp))
                    }
                }
            }
        }
        Text("Una técnica queda dominada con 85% o más en Practicar o Desafío.", style = T.tiny, modifier = Modifier.padding(top = 6.dp))
        val libres = buckets.flatMap { b -> Store.sessions.filter { it.libre && keyFn(it.t) == b.key } }
        if (libres.isNotEmpty()) {
            val xs = libres.mapNotNull { it.extra?.let { e -> runCatching { org.json.JSONObject(e) }.getOrNull() } }
            val cleans = xs.mapNotNull { x -> x.optInt("clean", -1).takeIf { it >= 0 } }
            val tops = HashMap<String, Int>()
            xs.forEach { x -> x.optJSONObject("tricks")?.let { o -> o.keys().forEach { k -> tops[k] = (tops[k] ?: 0) + o.getInt(k) } } }
            H2("Scratch libre")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Sesiones", "${libres.size}", "${libres.sumOf { it.dur } / 60} min", Modifier.weight(1f))
                Stat("Trucos", "${xs.sumOf { it.optInt("n") }}", tops.maxByOrNull { it.value }?.let { "más: ${it.key}" }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Cortes limpios", if (cleans.isEmpty()) "—" else "${cleans.sum() / cleans.size}%", modifier = Modifier.weight(1f))
                Stat("Mejor combo", libres.maxOf { it.combo }.let { if (it >= 2) "×$it" else "—" }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Toast() {
    val msg = Nav.toast ?: return
    androidx.compose.runtime.LaunchedEffect(msg) { kotlinx.coroutines.delay(1800); if (Nav.toast == msg) Nav.toast = null }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.clip(R12).background(C.raised).padding(horizontal = 16.dp, vertical = 10.dp)) { Text(msg, style = T.small.copy(color = C.text)) }
    }
}

@Composable
private fun CrashReport(text: String) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Modal("La app se cerró la última vez", { CrashLog.dismiss() }, maxWidth = 640.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Copiá el detalle y pasáselo a quien la está armando.", style = T.body.copy(color = C.text2))
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)).background(C.dark).verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(text, style = T.body.copy(fontFamily = Mono, fontSize = 11.sp, color = C.text2))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Copiar", kind = "ghost") { clip.setText(androidx.compose.ui.text.AnnotatedString(text)); Nav.toast = "Copiado" }
                Btn("Cerrar") { CrashLog.dismiss() }
            }
        }
    }
}
