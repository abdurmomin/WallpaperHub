@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.wallpaperhub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val ACC = Color(0xFF5B8CFF); val ACC2 = Color(0xFF9B6BFF)
val BG1 = Color(0xFF080C1A); val BG2 = Color(0xFF14183A); val BG3 = Color(0xFF1C1240)
val MUTED = Color(0xFF9AA4C0); val GLASS = Color(0x14FFFFFF); val EDGE = Color(0x24FFFFFF)
val GRAD = Brush.horizontalGradient(listOf(ACC, ACC2))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        Cfg.init(this)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACC, background = BG1, surface = BG2, onBackground = Color.White, onSurface = Color.White)) {
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
    val tabs = listOf("Discover" to Icons.Filled.Home, "Library" to Icons.Filled.Favorite, "Settings" to Icons.Filled.Settings)
    Surface(color = Color.Transparent, contentColor = Color.White,
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BG1, BG2, BG3)))) {
        Scaffold(containerColor = Color.Transparent, bottomBar = {
            NavigationBar(containerColor = Color(0xE60B1020)) {
                tabs.forEachIndexed { i, (l, ic) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White,
                            indicatorColor = ACC.copy(alpha = 0.40f), unselectedIconColor = MUTED, unselectedTextColor = MUTED))
                }
            }
        }) { pad -> Box(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) { when (tab) { 0 -> Discover(); 1 -> Library(); else -> SettingsPage() } } }
    }
}

// ---------- small building blocks ----------
@Composable fun Title(t: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = MUTED, fontSize = 14.sp)
    }
}

@Composable fun Section(t: String) = Text(t, color = ACC, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

@Composable fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GLASS).border(1.dp, EDGE, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable fun Pill(text: String, on: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(if (on) GRAD else SolidColor(GLASS)).border(1.dp, if (on) Color.Transparent else EDGE, shape)
        .combinedClickable(onClick = { onClick() }, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = if (on) Color.White else Color(0xFFC9D2EE), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable fun GradBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (enabled) GRAD else SolidColor(Color(0x33FFFFFF)))
        .clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable fun GlassBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.width(54.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x33FFFFFF)).border(1.dp, EDGE, RoundedCornerShape(24.dp))
        .clickable { onClick() }, contentAlignment = Alignment.Center) { Text(text, fontSize = 18.sp) }
}

@Composable fun SwRow(key: String, title: String, desc: String, def: Boolean, after: () -> Unit = {}) {
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(desc, color = MUTED, fontSize = 12.sp) }
            Switch(checked = Cfg.b(key, def), onCheckedChange = { Cfg.set(key, it); after() },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ACC, uncheckedThumbColor = Color(0xFFB0B8D0),
                    uncheckedTrackColor = Color(0x33FFFFFF), uncheckedBorderColor = Color.Transparent))
        }
    }
}

@Composable fun SlRow(key: String, title: String, desc: String, lo: Int, hi: Int, def: Int, unit: String = "", after: () -> Unit = {}) {
    var v by remember { mutableStateOf(Cfg.i(key, def).toFloat()) }
    Glass {
        Text("$title: ${v.toInt()}$unit", fontWeight = FontWeight.Bold); Text(desc, color = MUTED, fontSize = 12.sp)
        Slider(value = v, onValueChange = { v = it; Cfg.set(key, it.toInt()) }, valueRange = lo.toFloat()..hi.toFloat(), onValueChangeFinished = after,
            colors = SliderDefaults.colors(thumbColor = ACC, activeTrackColor = ACC, inactiveTrackColor = Color(0x33FFFFFF)))
    }
}

@Composable fun PillRow(key: String, title: String, opts: List<Pair<String, String>>, def: String, after: () -> Unit = {}) {
    Glass {
        Text(title, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEach { (v, l) -> Pill(l, Cfg.s(key, def) == v) { Cfg.set(key, v); after() } }
        }
    }
}

// ---------- Discover ----------
@Composable
fun Discover() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
    var kw by remember { mutableStateOf("") }
    val cur = Cfg.s("cur"); val file = Engine.find(ctx, cur)
    fun next() { scope.launch { busy = true; msg = Engine.changeNow(ctx, true); busy = false } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Title("Wallpaper Hub", "Fresh tall wallpapers — on every unlock.") }
        item {
            val shape = RoundedCornerShape(30.dp)
            Box(Modifier.fillMaxWidth().aspectRatio(0.66f).clip(shape).background(GLASS).border(1.dp, EDGE, shape)) {
                if (file != null) AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("Tap Next to get your first wallpaper", color = MUTED, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(Cfg.s("curTitle", ""), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
                    if (msg.isNotBlank()) Text(msg, color = Color(0xFF8FB0FF), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GradBtn(if (busy) "Loading…" else "↻   Next", Modifier.weight(1f), !busy) { next() }
                        GlassBtn("❤") { Engine.addFav(ctx, cur); msg = "Saved to favorites ❤" }
                        GlassBtn("🚫") { scope.launch { busy = true; Engine.block(ctx, cur); busy = false } }
                    }
                }
            }
        }
        item { SwRow("on", "Slideshow", "Keep changing wallpaper in the background", false) { setRunning(ctx, Cfg.b("on", false)) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("CATEGORIES")
                Text("⭐ = shown more often · long-press your own keyword to delete it", color = MUTED, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (Engine.CATS.keys + Cfg.list("custom").filter { it !in Engine.CATS }).forEach { n ->
                        val lg: (() -> Unit)? = if (n !in Engine.CATS) ({ removeCustom(n) }) else null
                        Pill((Engine.ICONS[n] ?: "🔎") + "  " + n + (if (n in Engine.DEF_PRIO) " ⭐" else ""), n in Cfg.list("cats", Engine.DEF_CATS), lg) { Cfg.toggle("cats", n, Engine.DEF_CATS, true) }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = kw, onValueChange = { kw = it }, singleLine = true, shape = RoundedCornerShape(24.dp),
                    placeholder = { Text("Add your own keyword…") }, modifier = Modifier.weight(1f))
                GradBtn("＋ Add", Modifier.width(92.dp)) {
                    val k = kw.replace(",", " ").trim().take(24)
                    if (k.isNotBlank() && k !in Engine.CATS && k !in Cfg.list("custom")) {
                        Cfg.set("custom", (Cfg.list("custom") + k).joinToString(",")); Cfg.toggle("cats", k, Engine.DEF_CATS, true)
                    }
                    kw = ""
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("SOURCES")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Engine.SOURCES.forEach { s ->
                        val key = s == "unsplash" || s == "pixabay"
                        Pill(s.replaceFirstChar { it.uppercase() } + if (key) "  🔑" else "", s in Cfg.list("src", Engine.DEF_SRC)) { Cfg.toggle("src", s, Engine.DEF_SRC, true) }
                    }
                }
                Text("🔑 needs a free API key (Settings). Unsplash & Pixabay give the best tall pictures.", color = MUTED, fontSize = 12.sp)
            }
        }
    }
}

// ---------- Library ----------
@Composable
fun Library() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var tabI by remember { mutableStateOf(0) }
    val cur = Cfg.s("cur")
    val files = remember(tabI, cur) { (if (tabI == 0) Engine.lib(ctx) else Engine.fav(ctx)).listFiles().orEmpty().sortedByDescending { it.lastModified() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Title("Library", "Saved on your phone — used automatically when you're offline.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("History", tabI == 0) { tabI = 0 }; Pill("❤ Favorites", tabI == 1) { tabI = 1 }
            Text("${files.size} pictures", color = MUTED, modifier = Modifier.padding(start = 6.dp))
        }
        if (files.isEmpty()) Text("Nothing here yet.", color = MUTED, modifier = Modifier.padding(top = 30.dp))
        LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(files, key = { it.path }) { f ->
                val shape = RoundedCornerShape(16.dp)
                AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(0.56f).clip(shape).then(if (f.name == cur) Modifier.border(2.dp, ACC, shape) else Modifier).clickable {
                        scope.launch(Dispatchers.IO) {
                            Engine.apply(ctx, f, Engine.titleOf(ctx, f.name))
                            withContext(Dispatchers.Main) { Toast.makeText(ctx, "Wallpaper set", Toast.LENGTH_SHORT).show() }
                        }
                    })
            }
        }
    }
}

// ---------- Settings ----------
@Composable
fun SettingsPage() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val re = { reapply(ctx, scope) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Settings", "Everything saves automatically.") }
        item { Section("WHEN TO CHANGE") }
        item { SwRow("unlock", "Every unlock", "New picture each time you unlock the phone", true) }
        item { SwRow("lock", "Every lock", "New picture each time the screen turns off", false) }
        item { SwRow("slide", "Slideshow timer", "Also change on a schedule", true) }
        item { SlRow("interval", "Slideshow every", "Minutes between changes", 1, 240, 30, " min") }
        item { Section("WHERE & HOW") }
        item { PillRow("target", "Apply to", listOf("home" to "Home", "lock" to "Lock", "both" to "Both"), "both") }
        item { PillRow("fit", "Picture fit", listOf("auto" to "Auto", "fill" to "Fill", "blur" to "Fit · blur", "black" to "Fit · black", "stretch" to "Stretch", "center" to "Center"), "auto") { re() } }
        item { SwRow("blur", "Blur wallpaper", "Soft background so icons stand out", false) { re() } }
        item { SlRow("blur_amt", "Blur strength", "Used when blur is on", 1, 30, 8, "", re) }
        item { SlRow("dim", "Dim picture", "Darken the wallpaper", 0, 70, 0, "%", re) }
        item { Section("QUALITY & OFFLINE") }
        item { SwRow("strict", "High quality only", "Skip low-resolution pictures", true) { Engine.reset() } }
        item { SwRow("crop", "Use wide photos", "Center-crop big landscape photos into tall ones. Off = only naturally tall pictures", true) { Engine.reset() } }
        item { SwRow("batt", "Battery saver", "Pause changes below 20% when not charging", true) }
        item { SlRow("libmax", "Library size", "Pictures kept for offline use", 30, 300, 100, " pics") }
        item {
            Button(onClick = { Engine.clearLibrary(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)), modifier = Modifier.fillMaxWidth()) {
                Text("Clear library (favorites are kept)")
            }
        }
        item { Section("SCREEN GESTURE") }
        item { SwRow("dtlock", "Double-tap to lock", "Double-tap an empty spot on the home screen to lock the phone", false) { if (Cfg.b("dtlock", false) && !dtOn(ctx)) openAccess(ctx) } }
        item { DoubleTapStatus() }
        item { Section("API KEYS (OPTIONAL)") }
        item { KeyField("unsplash_key", "Unsplash Access Key") }
        item { KeyField("pixabay_key", "Pixabay key") }
        item { Text("Tip: for reliable changes on every unlock set Battery → Unrestricted for this app.", color = MUTED, fontSize = 12.sp) }
    }
}

fun removeCustom(n: String) {
    Cfg.set("custom", Cfg.list("custom").filter { it != n }.joinToString(","))
    val cats = Cfg.list("cats", Engine.DEF_CATS).filter { it != n }
    Cfg.set("cats", (if (cats.isEmpty()) Engine.DEF_CATS.split(",").take(1) else cats).joinToString(",")); Engine.reset()
}

fun dtOn(c: Context): Boolean =
    (android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
        .contains(c.packageName + "/" + DoubleTapService::class.java.name)

fun openAccess(c: Context) {
    c.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable fun DoubleTapStatus() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val on = remember(tick) { dtOn(ctx) }
    Glass {
        Text(if (on) "✓ Accessibility access is ON — double-tap lock is ready" else "One-time permission needed: Accessibility → Wallpaper Hub → turn on",
            fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (on) Color(0xFF6EE7A8) else Color(0xFFFFC857))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradBtn("Open settings", Modifier.weight(1f)) { openAccess(ctx) }
            GradBtn("Check again", Modifier.weight(1f)) { tick++ }
        }
        Text("Works on the home screen only, so double-taps inside other apps are never affected. Waking a dark screen by double-tap is not possible for apps — use your phone's own \"Double tap to wake\" option (usually under Display, Advanced features or Gestures).", color = MUTED, fontSize = 12.sp)
    }
}

@Composable fun KeyField(key: String, label: String) {
    var v by remember { mutableStateOf(Cfg.s(key)) }
    OutlinedTextField(value = v, onValueChange = { v = it; Cfg.set(key, it.trim()); Engine.reset() }, label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
}
