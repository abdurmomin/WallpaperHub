package com.wallpaperhub

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.pow
import kotlin.random.Random

/** Settings: a Compose-observable map that is mirrored to SharedPreferences. */
object Cfg {
    private lateinit var sp: SharedPreferences
    val m = mutableStateMapOf<String, String>()
    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.applicationContext.getSharedPreferences("cfg", 0)
        sp.all.forEach { (k, v) -> m[k] = v.toString() }
    }
    fun s(k: String, d: String = "") = m[k] ?: d
    fun b(k: String, d: Boolean) = m[k]?.toBoolean() ?: d
    fun i(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
    fun list(k: String, d: String = "") = s(k, d).split(",").filter { it.isNotBlank() }
    fun lines(k: String) = s(k).split("\n").filter { it.isNotBlank() }
    fun set(k: String, v: Any) { m[k] = v.toString(); sp.edit().putString(k, v.toString()).apply() }
    fun toggle(key: String, item: String, def: String, minOne: Boolean) {
        val l = list(key, def).toMutableList()
        if (item in l) { if (!minOne || l.size > 1) l.remove(item) } else l.add(item)
        set(key, l.joinToString(",")); Engine.reset()
    }
}

object Engine {
    const val UA = "WallpaperHub/1.0 (Android)"
    const val DEF_CATS = "Flowers,Switzerland,France,Birds,City,Mountains"
    val DEF_PRIO = setOf("Flowers", "Switzerland", "France", "Birds", "City")   // shown more often
    const val DEF_SRC = "reddit,wikimedia,bing"
    val CATS = linkedMapOf(
        "Flowers" to "flowers blossom garden", "Switzerland" to "switzerland alps village lake", "France" to "france paris eiffel provence village", "Birds" to "bird wildlife colorful",
        "Ocean" to "ocean beach sea", "Mountains" to "mountain peak snow", "City" to "city skyline night lights",
        "Sunset" to "sunset sky clouds", "Waterfall" to "waterfall river", "Forest" to "forest trees fog",
        "Space" to "nebula galaxy astronomy", "Animals" to "wildlife animal", "Abstract" to "abstract pattern art")
    val ICONS = mapOf("Flowers" to "🌸", "Switzerland" to "🇨🇭", "France" to "🇫🇷", "Birds" to "🦜", "Ocean" to "🌊", "Mountains" to "🏔️", "City" to "🌃",
        "Sunset" to "🌅", "Waterfall" to "💧", "Forest" to "🌲", "Space" to "🌌", "Animals" to "🦁", "Abstract" to "🎨")
    val SOURCES = listOf("bing", "wikimedia", "nasa", "reddit", "unsplash", "pixabay")
    private val REDDIT = mapOf("Flowers" to "flowers", "Switzerland" to "Switzerland", "France" to "CityPorn", "Birds" to "birding", "Ocean" to "oceanporn",
        "Mountains" to "MountainPorn", "City" to "CityPorn", "Sunset" to "SkyPorn", "Waterfall" to "WaterfallPorn", "Forest" to "ForestPorn", "Space" to "spaceporn")
    private val pool = ArrayList<Pair<String, String>>()
    private val catOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun tag(cat: String, l: List<Pair<String, String>>): List<Pair<String, String>> { l.forEach { catOf[it.first] = cat }; return l }
    // picture file name -> last time it was shown (persisted); a normal picture is not shown again for 120 days
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var seenReady = false
    private fun nameOf(u: String) = md5(u) + ".jpg"
    private fun seenInit(c: Context) {
        appCtx = c.applicationContext
        if (seenReady) return; seenReady = true
        val sp = c.getSharedPreferences("seen", 0); val ed = sp.edit(); val cut = System.currentTimeMillis() - 120 * 86_400_000L
        for ((k, v) in sp.all) { if (v is Long && v > cut) seen[k] = v else ed.remove(k) }
        ed.apply()
        for (f in fav(c).listFiles()?.toList() ?: emptyList<File>()) seen.putIfAbsent(f.name, f.lastModified())
    }
    private fun mark(c: Context, name: String) {
        val t = System.currentTimeMillis(); seen[name] = t
        c.getSharedPreferences("seen", 0).edit().putLong(name, t).apply()
    }
    private val mutex = Mutex()
    private val poolLock = Mutex()
    @Volatile private var dirty = false
    @Volatile private var since = 0L
    private var appCtx: Context? = null

    fun reset() { dirty = true; since = System.currentTimeMillis() }
    fun term(cat: String) = CATS[cat] ?: cat
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------- folders / metadata ----------
    fun lib(c: Context) = File(c.filesDir, "lib").apply { mkdirs() }
    fun fav(c: Context) = File(c.filesDir, "fav").apply { mkdirs() }
    private fun meta(c: Context) = c.getSharedPreferences("meta", 0)
    fun titleOf(c: Context, name: String) = meta(c).getString(name, "Saved picture").orEmpty()
    fun find(c: Context, name: String): File? = listOf(File(lib(c), name), File(fav(c), name)).firstOrNull { name.isNotBlank() && it.exists() }
    private fun allFiles(c: Context): List<File> = (lib(c).listFiles()?.toList() ?: emptyList<File>()) + (fav(c).listFiles()?.toList() ?: emptyList<File>())
    fun libCount(c: Context) = lib(c).listFiles().orEmpty().size

    // ---------- network ----------
    private fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val h = URL(url).openConnection() as HttpURLConnection
        h.connectTimeout = 15000; h.readTimeout = 30000; h.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> h.setRequestProperty(k, v) }
        return h.inputStream.use { it.readBytes() }
    }
    private fun json(url: String) = String(get(url))
    fun online(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // ---------- sources: each returns [(url, title)] ----------
    private fun bing(): List<Pair<String, String>> = listOf("en-US", "en-GB", "en-IN", "ja-JP", "de-DE").flatMap { m ->
        val a = JSONObject(json("https://www.bing.com/HPImageArchive.aspx?format=js&idx=${Random.nextInt(8)}&n=8&mkt=$m")).getJSONArray("images")
        (0 until a.length()).map { a.getJSONObject(it) }.map { "https://www.bing.com" + it.getString("urlbase") + "_1080x1920.jpg" to it.optString("title", "Bing") }
    }
    private fun wiki(cat: String): List<Pair<String, String>> {
        val q = enc(term(cat) + " filetype:bitmap")
        val r = JSONObject(json("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=30&gsroffset=${Random.nextInt(300)}&gsrsearch=$q&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=2560"))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k); val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (dimsOk(ii.optInt("width"), ii.optInt("height")) && ii.has("thumburl")) out.add(ii.getString("thumburl") to p.getString("title").removePrefix("File:"))
        }
        return out
    }
    private fun nasa(): List<Pair<String, String>> {
        val a = JSONArray(json("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&count=25"))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("media_type") == "image" && it.has("hdurl") }
            .map { it.getString("hdurl") to it.optString("title", "NASA") }
    }
    private fun redditPosts(url: String): List<Pair<String, String>> {
        val a = JSONObject(json(url)).getJSONObject("data").getJSONArray("children")
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until a.length()) {
            val d = a.getJSONObject(i).getJSONObject("data")
            val u = d.optString("url").lowercase()
            if (!(u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png"))) continue
            val src = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source") ?: continue
            if (!dimsOk(src.optInt("width"), src.optInt("height"))) continue
            out.add(d.getString("url") to "r/" + d.optString("subreddit") + " - " + d.optString("title").take(60))
        }
        return out
    }
    private fun reddit(cat: String): List<Pair<String, String>> {
        val t = listOf("month", "year", "all").random(); val q = enc(term(cat).split(" ")[0])
        val phone = runCatching { redditPosts("https://www.reddit.com/r/phonewallpapers/search.json?q=$q&restrict_sr=1&sort=top&t=$t&limit=60") }.getOrDefault(emptyList())
        val wide = runCatching { redditPosts("https://www.reddit.com/r/${REDDIT[cat] ?: "wallpapers"}/top.json?t=$t&limit=60") }.getOrDefault(emptyList())
        return phone + wide
    }
    private fun unsplash(cat: String, key: String): List<Pair<String, String>> {
        val a = JSONArray(json("https://api.unsplash.com/photos/random?query=${enc(term(cat).split(" ")[0])}&count=30&orientation=portrait&client_id=$key"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            it.getJSONObject("urls").getString("raw") + "&w=2160&fit=max&fm=jpg" to "Unsplash - " + it.getJSONObject("user").getString("name")
        }
    }
    private fun pixabay(cat: String, key: String): List<Pair<String, String>> {
        val a = JSONObject(json("https://pixabay.com/api/?key=$key&q=${enc(term(cat).split(" ")[0])}&orientation=vertical&image_type=photo&min_height=1920&per_page=30&page=${Random.nextInt(1, 8)}")).getJSONArray("hits")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            (it.optString("fullHDURL").ifBlank { it.optString("imageURL").ifBlank { it.getString("largeImageURL") } }) to "Pixabay - " + it.optString("user")
        }
    }

    private suspend fun gather(): List<Pair<String, String>> {
        val srcs = Cfg.list("src", DEF_SRC)
        val jobs = ArrayList<() -> List<Pair<String, String>>>()
        if ("bing" in srcs) jobs.add { bing() }
        if ("nasa" in srcs) jobs.add { nasa() }
        val uk = Cfg.s("unsplash_key"); val pk = Cfg.s("pixabay_key")
        for (c in Cfg.list("cats", DEF_CATS)) {
            if ("wikimedia" in srcs) jobs.add { tag(c, wiki(c)) }
            if ("reddit" in srcs) jobs.add { tag(c, reddit(c)) }
            if ("unsplash" in srcs && uk.isNotBlank()) jobs.add { tag(c, unsplash(c, uk)) }
            if ("pixabay" in srcs && pk.isNotBlank()) jobs.add { tag(c, pixabay(c, pk)) }
        }
        val all = coroutineScope { jobs.map { j -> async(Dispatchers.IO) { runCatching { j() }.getOrDefault(emptyList()) } }.awaitAll() }.flatten()
        val blocked = Cfg.lines("block").toSet()
        val m = LinkedHashMap<String, String>(); all.forEach { m[it.first] = it.second }
        return m.entries.filter { it.key !in blocked }.map { it.key to it.value }
    }

    private suspend fun refill() {
        var items = emptyList<Pair<String, String>>(); var fresh = items
        for (i in 0 until 3) {                      // sources are randomised, so retrying usually finds new pictures
            items = gather()
            fresh = items.filter { !seen.containsKey(nameOf(it.first)) }
            if (fresh.isNotEmpty()) break
        }
        if (fresh.isEmpty()) {                      // truly nothing new: only pictures not shown for 7+ days, oldest first
            val week = System.currentTimeMillis() - 7 * 86_400_000L
            fresh = items.sortedBy { seen[nameOf(it.first)] ?: 0L }.filter { (seen[nameOf(it.first)] ?: 0L) < week }.take(5)
        }
        fun w(u: String) = if ((catOf[u] ?: "") in DEF_PRIO) 3.0 else 1.0
        pool.clear(); pool.addAll(fresh.sortedBy { Random.nextDouble().pow(1.0 / w(it.first)) })   // pool pops from the end: priority categories tend to come first
    }

    // ---------- screen, shape check, download ----------
    @Suppress("DEPRECATION")
    fun screen(c: Context): IntArray {
        val dm = DisplayMetrics()
        (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return intArrayOf(minOf(dm.widthPixels, dm.heightPixels), maxOf(dm.widthPixels, dm.heightPixels))
    }
    /** Size the picture would have after cropping to a tall phone shape, or null if it is not good enough. */
    private fun fit(c: Context, w: Int, h: Int): IntArray? {
        val sc = screen(c); val t = sc[0].toFloat() / sc[1]; val ar = w.toFloat() / h
        var cw = w; var ch = h
        if (ar > t * 1.45f) { if (!Cfg.b("crop", true)) return null; cw = (h * t).toInt() }
        else if (ar < t * 0.8f) ch = (w / t).toInt()
        val k = if (Cfg.b("strict", true)) 0.9f else 0.7f
        return if (cw >= sc[0] * k && ch >= sc[1] * k) intArrayOf(cw, ch) else null
    }
    private fun dimsOk(w: Int, h: Int): Boolean {
        val c = appCtx ?: return true
        if (w <= 0 || h <= 0) return true
        return fit(c, w, h) != null
    }
    private fun download(c: Context, url: String): File? {
        val f = File(lib(c), md5(url) + ".jpg")
        if (f.exists()) return f
        val bytes = get(url)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val plan = (if (o.outWidth > 0) fit(c, o.outWidth, o.outHeight) else null) ?: return null
        val sc = screen(c); val t = sc[0].toFloat() / sc[1]
        var s = 1
        while (plan[1] / (s * 2) >= sc[1] * 1.25 && plan[0] / (s * 2) >= sc[0] * 1.25) s *= 2
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val ar = full.width.toFloat() / full.height
        val bmp = when {
            ar > t * 1.45f -> { val nw = (full.height * t).toInt().coerceIn(1, full.width); Bitmap.createBitmap(full, (full.width - nw) / 2, 0, nw, full.height) }
            ar < t * 0.8f -> { val nh = (full.width / t).toInt().coerceIn(1, full.height); Bitmap.createBitmap(full, 0, (full.height - nh) / 2, full.width, nh) }
            else -> full
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (bmp !== full) bmp.recycle()
        full.recycle()
        meta(c).edit().putString(f.name + ".u", url).apply()
        return f
    }
    private suspend fun fetchOne(c: Context): Pair<File, String>? = poolLock.withLock { fetchLocked(c) }

    private suspend fun fetchLocked(c: Context): Pair<File, String>? {
        if (dirty) { dirty = false; pool.clear() }
        repeat(6) {
            if (pool.isEmpty()) refill()
            if (pool.isEmpty()) return null
            val (u, t) = pool.removeAt(pool.size - 1)
            val f = runCatching { download(c, u) }.getOrNull()
            if (f == null) { mark(c, nameOf(u)); return@repeat }     // rejected / failed: don't try it again
            meta(c).edit().putString(f.name, t).apply()
            return f to t
        }
        return null
    }
    private fun randomLocal(c: Context): Pair<File, String>? {
        val cur = Cfg.s("cur")
        val all = allFiles(c).filter { it.name != cur }
        val sorted = all.distinctBy { it.name }.sortedBy { seen[it.name] ?: 0L }
        val f = sorted.take(maxOf(1, sorted.size / 2)).randomOrNull() ?: return null
        return f to titleOf(c, f.name)
    }
    private fun readyFiles(c: Context): List<File> {
        val cur = Cfg.s("cur")
        return lib(c).listFiles()?.filter { it.name != cur && !seen.containsKey(it.name) && it.lastModified() >= since } ?: emptyList()
    }
    private val filling = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Keep a few unseen pictures downloaded so the next change is instant (and works offline). */
    fun topUp(c: Context) {
        if (!filling.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var n = 0
                while (online(c) && n < 8 && readyFiles(c).size < 8 && libCount(c) < Cfg.i("libmax", 100)) { runCatching { fetchOne(c) }; n++ }
            } finally { filling.set(false) }
        }
    }
    /** Every now and then bring back a favorite (never twice within 24 hours). */
    private fun pickFav(c: Context): Pair<File, String>? {
        val cur = Cfg.s("cur"); val now = System.currentTimeMillis()
        val f = fav(c).listFiles().orEmpty().filter { it.name != cur && now - (seen[it.name] ?: 0L) > 24 * 3_600_000L }.randomOrNull() ?: return null
        return f to titleOf(c, f.name)
    }

    // ---------- render to screen + set wallpaper ----------
    private fun mut(b: Bitmap) = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
    private fun blur(b: Bitmap, level: Int): Bitmap {
        val k = maxOf(2, level) * 2
        val small = Bitmap.createScaledBitmap(b, maxOf(1, b.width / k), maxOf(1, b.height / k), true)
        return mut(Bitmap.createScaledBitmap(small, b.width, b.height, true))
    }
    private fun render(c: Context, f: File): Bitmap {
        val sc = screen(c); val sw = sc[0]; val sh = sc[1]
        val src = BitmapFactory.decodeFile(f.path)
        var out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun draw(cv: Canvas, s: Float) {
            val w = src.width * s; val h = src.height * s
            cv.drawBitmap(src, null, RectF((sw - w) / 2, (sh - h) / 2, (sw + w) / 2, (sh + h) / 2), paint)
        }
        val fillS = maxOf(sw.toFloat() / src.width, sh.toFloat() / src.height)
        val fitS = minOf(sw.toFloat() / src.width, sh.toFloat() / src.height)
        var mode = Cfg.s("fit", "auto")
        if (mode == "auto") {   // close to the screen shape -> fill, otherwise whole picture with soft bars
            val t = sw.toFloat() / sh; val ar = src.width.toFloat() / src.height
            mode = if (ar >= t * 0.75f && ar <= t * 1.35f) "fill" else "blur"
        }
        when (mode) {
            "fill" -> draw(Canvas(out), fillS)
            "stretch" -> Canvas(out).drawBitmap(src, null, RectF(0f, 0f, sw.toFloat(), sh.toFloat()), paint)
            "center" -> { val cv = Canvas(out); cv.drawColor(Color.BLACK); draw(cv, 1f) }
            "black" -> { val cv = Canvas(out); cv.drawColor(Color.BLACK); draw(cv, fitS) }
            else -> { draw(Canvas(out), fillS); out = blur(out, 12); val cv = Canvas(out); cv.drawColor(0x88000000.toInt()); draw(cv, fitS) }
        }
        src.recycle()
        if (Cfg.b("blur", false)) out = blur(out, Cfg.i("blur_amt", 8))
        val dim = Cfg.i("dim", 0)
        if (dim > 0) Canvas(out).drawColor((dim * 255 / 100) shl 24)
        return out
    }
    fun apply(c: Context, f: File, title: String) {
        val bmp = render(c, f); val wm = WallpaperManager.getInstance(c)
        val sc = screen(c)
        runCatching { wm.suggestDesiredDimensions(sc[0], sc[1]) }       // screen-sized, no sideways scrolling / zoom
        val hint = Rect(0, 0, bmp.width, bmp.height)
        val home = WallpaperManager.FLAG_SYSTEM; val lock = WallpaperManager.FLAG_LOCK
        when (Cfg.s("target", "both")) {
            "home" -> wm.setBitmap(bmp, hint, true, home)
            "lock" -> wm.setBitmap(bmp, hint, true, lock)
            else -> wm.setBitmap(bmp, hint, true, home or lock)
        }
        bmp.recycle()
        seenInit(c); mark(c, f.name)
        Cfg.set("cur", f.name); Cfg.set("curTitle", title)
        val keep = Cfg.i("libmax", 100)
        lib(c).listFiles().orEmpty().filter { it.name != f.name }.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    // ---------- public actions ----------
    /** Online: fetch a fresh picture. Offline (or fetch failed): pick one from the Library. */
    suspend fun changeNow(c: Context, force: Boolean = false): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!force && Cfg.b("batt", true)) {
                val bm = c.getSystemService(BatteryManager::class.java)
                if (bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < 20 && !bm.isCharging) return@withContext "Battery saver is on"
            }
            seenInit(c)
            var got: Pair<File, String>? = (if (Random.nextFloat() < 0.2f) pickFav(c) else null)
                ?: readyFiles(c).randomOrNull()?.let { it to titleOf(c, it.name) }       // already downloaded -> instant
            if (got == null && online(c)) got = runCatching { fetchOne(c) }.getOrNull()
            val offline = got == null
            if (got == null) got = randomLocal(c)
            val g = got ?: return@withContext "Nothing to show yet — connect to the internet once"
            apply(c, g.first, g.second)
            topUp(c)
            if (offline) "Offline — picked from your Library" else "New picture applied"
        }
    }
    fun addFav(c: Context, name: String) { find(c, name)?.copyTo(File(fav(c), name), true) }
    suspend fun block(c: Context, name: String) {
        val url = meta(c).getString("$name.u", null)
        if (url != null) Cfg.set("block", (Cfg.lines("block") + url).joinToString("\n"))
        File(lib(c), name).delete()
        changeNow(c, true)
    }
    fun clearLibrary(c: Context) { lib(c).listFiles().orEmpty().forEach { it.delete() } }
}
