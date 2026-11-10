package com.wallpaperhub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val ACC = Color(0xFF3B82F6); val BG = Color(0xFF0B1220); val CARD = Color(0xFF162033); val MUTED = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.init(this)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACC, background = BG, surface = CARD, onBackground = Color.White, onSurface = Color.White)) {
                LaunchedEffect(Unit) { if (Cfg.b("on", false)) setRunning(this@MainActivity, true) }
                App()
            }
        }
    }
}

fun setRunning(c: Context, on: Boolean) {
    Cfg.set("on", on)
    val i = Intent(c, HubService::class.java)
    if (on) ContextCompat.startForegroundService(c, i) else c.stopService(i)
}

fun reapply(c: Context, scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) { Engine.find(c, Cfg.s("cur"))?.let { Engine.apply(c, it, Cfg.s("curTitle")) } }
}

@Composable
fun App() {
    var tab by remember { mutableStateOf(0) }
    val items = listOf("Discover" to Icons.Filled.Home, "Library" to Icons.Filled.Favorite, "Settings" to Icons.Filled.Settings)
    Scaffold(containerColor = BG, bottomBar = {
        NavigationBar(containerColor = CARD) {
            items.forEachIndexed { i, (l, ic) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l) }) }
        }
    }) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { when (tab) { 0 -> Discover(); 1 -> Library(); else -> SettingsPage() } } }
}

@Composable fun Head(t: String, sub: String) {
    Column { Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(sub, color = MUTED, fontSize = 13.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Discover() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
    var kw by remember { mutableStateOf("") }
    val cur = Cfg.s("cur"); val file = Engine.find(ctx, cur)
    fun next() { scope.launch { busy = true; msg = Engine.changeNow(ctx, true); busy = false } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Head("Discover", "Pick what you like — your wallpaper & lock screen update automatically.") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
                        if (file != null) AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text("No picture yet — press Next", color = MUTED)
                    }
                    Text(Cfg.s("curTitle", "—"), fontWeight = FontWeight.Bold)
                    if (msg.isNotBlank()) Text(msg, color = ACC, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { next() }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Refresh, null); Text(if (busy) "  Loading…" else "  Next") }
                        Button(onClick = { Engine.addFav(ctx, cur); msg = "Saved to favorites ❤" }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26334D)), modifier = Modifier.weight(1f)) { Text("❤ Save") }
                        Button(onClick = { scope.launch { busy = true; Engine.block(ctx, cur); busy = false } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)), modifier = Modifier.weight(1f)) { Text("🚫 Block") }
                    }
                }
            }
        }
        item { Sw("on", "Slideshow", "Keep changing wallpaper in the background", false) { setRunning(ctx, Cfg.b("on", false)) } }
        item {
            Text("Categories", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (Engine.CATS.keys + Cfg.list("custom")).forEach { n ->
                    FilterChip(selected = n in Cfg.list("cats", "Nature,Space"), onClick = { Cfg.toggle("cats", n, "Nature,Space", true) }, label = { Text((Engine.ICONS[n] ?: "🔎") + " " + n) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = kw, onValueChange = { kw = it }, singleLine = true, placeholder = { Text("Add your own keyword…") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    val k = kw.replace(",", " ").trim().take(24)
                    if (k.isNotBlank() && k !in Engine.CATS && k !in Cfg.list("custom")) { Cfg.set("custom", (Cfg.list("custom") + k).joinToString(",")); Cfg.toggle("cats", k, "Nature,Space", true) }
                    kw = ""
                }) { Text("＋ Add") }
            }
        }
        item {
            Text("Sources", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Engine.SOURCES.forEach { s ->
                    val key = s == "unsplash" || s == "pixabay"
                    FilterChip(selected = s in Cfg.list("src", "bing,wikimedia,nasa"), onClick = { Cfg.toggle("src", s, "bing,wikimedia,nasa", true) }, label = { Text(s.replaceFirstChar { it.uppercase() } + if (key) " (key)" else "") })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Library() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var tabI by remember { mutableStateOf(0) }
    val cur = Cfg.s("cur")
    val files = remember(tabI, cur) { (if (tabI == 0) Engine.lib(ctx) else Engine.fav(ctx)).listFiles().orEmpty().sortedByDescending { it.lastModified() } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("Library", "Saved on your phone. Tap a picture to set it. Used automatically when you're offline.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tabI == 0, onClick = { tabI = 0 }, label = { Text("History") })
            FilterChip(selected = tabI == 1, onClick = { tabI = 1 }, label = { Text("Favorites") })
            Text("${files.size} pictures", color = MUTED, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
        }
        if (files.isEmpty()) Text("Nothing here yet.", color = MUTED, modifier = Modifier.padding(top = 40.dp))
        LazyVerticalGrid(GridCells.Fixed(2), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.path }) { f ->
                AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(0.62f).clip(RoundedCornerShape(12.dp)).clickable {
                        scope.launch(Dispatchers.IO) { Engine.apply(ctx, f, Engine.titleOf(ctx, f.name)) }
                    })
            }
        }
    }
}

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val re = { reapply(ctx, scope) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Head("Settings", "Everything saves automatically.") }
        item { Text("When to change", fontWeight = FontWeight.Bold) }
        item { Sw("unlock", "Every unlock", "New picture each time you unlock the phone", true) }
        item { Sw("lock", "Every lock", "New picture each time the screen turns off", false) }
        item { Sw("slide", "Slideshow timer", "Also change on a schedule", true) }
        item { Sl("interval", "Slideshow every", "Minutes between changes", 1, 240, 30, " min") }
        item { Text("Where & how", fontWeight = FontWeight.Bold) }
        item { Chips("target", listOf("home" to "Home", "lock" to "Lock", "both" to "Both"), "both") {} }
        item { Chips("fit", listOf("blur" to "Fit (blurred bars)", "black" to "Fit (black bars)", "fill" to "Fill (crop)"), "blur") { re() } }
        item { Sw("blur", "Blur wallpaper", "Soft background so icons stand out", false) { re() } }
        item { Sl("blur_amt", "Blur strength", "Used when blur is on", 1, 30, 8, "", re) }
        item { Sl("dim", "Dim picture", "Darken the wallpaper", 0, 70, 0, "%", re) }
        item { Text("Quality & offline", fontWeight = FontWeight.Bold) }
        item { Sw("strict", "High quality only", "Skip small, low-resolution pictures", true) { Engine.reset() } }
        item { Sw("batt", "Battery saver", "Pause changes below 20% when not charging", true) }
        item { Sl("libmax", "Library size", "Pictures kept for offline use", 30, 300, 100, " pics") }
        item { Button(onClick = { Engine.clearLibrary(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))) { Text("Clear library (favorites are kept)") } }
        item { Text("API keys (optional)", fontWeight = FontWeight.Bold) }
        item { KeyField("unsplash_key", "Unsplash Access Key") }
        item { KeyField("pixabay_key", "Pixabay key") }
        item { Text("Tip: for reliable changes on every unlock, set Battery → Unrestricted for this app.", color = MUTED, fontSize = 12.sp) }
    }
}

@Composable fun KeyField(key: String, label: String) {
    var v by remember { mutableStateOf(Cfg.s(key)) }
    OutlinedTextField(value = v, onValueChange = { v = it; Cfg.set(key, it.trim()); Engine.reset() }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable fun Row2(title: String, desc: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(desc, color = MUTED, fontSize = 12.sp) }
            content()
        }
    }
}

@Composable fun Sw(key: String, title: String, desc: String, def: Boolean, after: () -> Unit = {}) =
    Row2(title, desc) { Switch(checked = Cfg.b(key, def), onCheckedChange = { Cfg.set(key, it); after() }) }

@Composable fun Sl(key: String, title: String, desc: String, lo: Int, hi: Int, def: Int, unit: String = "", after: () -> Unit = {}) {
    var v by remember { mutableStateOf(Cfg.i(key, def).toFloat()) }
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("$title: ${v.toInt()}$unit", fontWeight = FontWeight.Bold); Text(desc, color = MUTED, fontSize = 12.sp)
            Slider(value = v, onValueChange = { v = it; Cfg.set(key, it.toInt()) }, valueRange = lo.toFloat()..hi.toFloat(), onValueChangeFinished = after)
        }
    }
}

@Composable fun Chips(key: String, opts: List<Pair<String, String>>, def: String, after: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { (v, l) -> FilterChip(selected = Cfg.s(key, def) == v, onClick = { Cfg.set(key, v); after() }, label = { Text(l, fontSize = 12.sp) }) }
    }
}
