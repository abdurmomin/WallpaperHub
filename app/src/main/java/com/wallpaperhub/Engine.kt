package com.wallpaperhub

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
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
    val CATS = linkedMapOf(
        "Nature" to "landscape nature scenery", "Space" to "nebula galaxy astronomy", "City" to "city skyline night",
        "Animals" to "wildlife animal", "Ocean" to "ocean beach sea", "Mountains" to "mountain peak",
        "Abstract" to "abstract pattern art", "Architecture" to "architecture building")
    val ICONS = mapOf("Nature" to "🌿", "Space" to "🌌", "City" to "🌃", "Animals" to "🦁", "Ocean" to "🌊",
        "Mountains" to "🏔️", "Abstract" to "🎨", "Architecture" to "🏛️")
    val SOURCES = listOf("bing", "wikimedia", "nasa", "reddit", "unsplash", "pixabay")
    private val REDDIT = mapOf("Nature" to "EarthPorn", "Space" to "spaceporn", "City" to "CityPorn", "Architecture" to "ArchitecturePorn")
    private val pool = ArrayList<Pair<String, String>>()
    // picture file name -> last time it was shown (persisted); a normal picture is not shown again for 120 days
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var seenReady = false
    private fun nameOf(u: String) = md5(u) + ".jpg"
    private fun seenInit(c: Context) {
        if (seenReady) return; seenReady = true
        val sp = c.getSharedPreferences("seen", 0); val ed = sp.edit(); val cut = System.currentTimeMillis() - 120 * 86_400_000L
        for ((k, v) in sp.all) { if (v is Long && v > cut) seen[k] = v else ed.remove(k) }
        ed.apply()
        for (f in allFiles(c)) seen.putIfAbsent(f.name, f.lastModified())
    }
    private fun mark(c: Context, name: String) {
        val t = System.currentTimeMillis(); seen[name] = t
        c.getSharedPreferences("seen", 0).edit().putLong(name, t).apply()
    }
    private val mutex = Mutex()

    fun reset() = pool.clear()
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
        (0 until a.length()).map { a.getJSONObject(it) }.map { "https://www.bing.com" + it.getString("urlbase") + "_UHD.jpg" to it.optString("title", "Bing") }
    }
    private fun wiki(cat: String): List<Pair<String, String>> {
        val q = enc(term(cat) + " filetype:bitmap")
        val r = JSONObject(json("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=30&gsroffset=${Random.nextInt(300)}&gsrsearch=$q&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=2560"))
        val pages = r.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k); val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (ii.optInt("width") >= 1920 && ii.has("thumburl")) out.add(ii.getString("thumburl") to p.getString("title").removePrefix("File:"))
        }
        return out
    }
    private fun nasa(): List<Pair<String, String>> {
        val a = JSONArray(json("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&count=25"))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("media_type") == "image" && it.has("hdurl") }
            .map { it.getString("hdurl") to it.optString("title", "NASA") }
    }
    private fun reddit(cat: String): List<Pair<String, String>> {
        val a = JSONObject(json("https://www.reddit.com/r/${REDDIT[cat] ?: "wallpapers"}/top.json?t=${listOf("week", "month", "year", "all").random()}&limit=60")).getJSONObject("data").getJSONArray("children")
        return (0 until a.length()).map { a.getJSONObject(it).getJSONObject("data") }.filter {
            val u = it.optString("url").lowercase(); u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png")
        }.map { it.getString("url") to "r/" + it.optString("subreddit") + " - " + it.optString("title").take(60) }
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
        val srcs = Cfg.list("src", "bing,wikimedia,nasa")
        val jobs = ArrayList<() -> List<Pair<String, String>>>()
        if ("bing" in srcs) jobs.add { bing() }
        if ("nasa" in srcs) jobs.add { nasa() }
        val uk = Cfg.s("unsplash_key"); val pk = Cfg.s("pixabay_key")
        for (c in Cfg.list("cats", "Nature,Space")) {
            if ("wikimedia" in srcs) jobs.add { wiki(c) }
            if ("reddit" in srcs) jobs.add { reddit(c) }
            if ("unsplash" in srcs && uk.isNotBlank()) jobs.add { unsplash(c, uk) }
            if ("pixabay" in srcs && pk.isNotBlank()) jobs.add { pixabay(c, pk) }
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
        pool.clear(); pool.addAll(fresh.shuffled())
    }

    // ---------- download + quality filter ----------
    private fun good(c: Context, w: Int, h: Int): Boolean {
        val d = c.resources.displayMetrics
        val sl = maxOf(d.widthPixels, d.heightPixels); val ss = minOf(d.widthPixels, d.heightPixels)
        val ar = w.toFloat() / h
        if (ar < 0.4f || ar > 2.6f) return false
        return if (Cfg.b("strict", true)) maxOf(w, h) >= sl * 0.9 && minOf(w, h) >= ss * 0.9 else maxOf(w, h) >= 1280
    }
    private fun download(c: Context, url: String): File? {
        val f = File(lib(c), md5(url) + ".jpg")
        if (f.exists()) return f
        val bytes = get(url)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || !good(c, o.outWidth, o.outHeight)) return null
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 3000) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        meta(c).edit().putString(f.name + ".u", url).apply()
        return f
    }
    private suspend fun fetchOne(c: Context): Pair<File, String>? {
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
        val d = c.resources.displayMetrics; val sw = d.widthPixels; val sh = d.heightPixels
        val src = BitmapFactory.decodeFile(f.path)
        var out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        fun draw(cv: Canvas, fill: Boolean) {
            val a = sw.toFloat() / src.width; val b = sh.toFloat() / src.height
            val s = if (fill) maxOf(a, b) else minOf(a, b); val w = src.width * s; val h = src.height * s
            cv.drawBitmap(src, null, RectF((sw - w) / 2, (sh - h) / 2, (sw + w) / 2, (sh + h) / 2), paint)
        }
        when (Cfg.s("fit", "blur")) {
            "fill" -> draw(Canvas(out), true)
            "black" -> { val cv = Canvas(out); cv.drawColor(Color.BLACK); draw(cv, false) }
            else -> { draw(Canvas(out), true); out = blur(out, 12); val cv = Canvas(out); cv.drawColor(0x88000000.toInt()); draw(cv, false) }
        }
        src.recycle()
        if (Cfg.b("blur", false)) out = blur(out, Cfg.i("blur_amt", 8))
        val dim = Cfg.i("dim", 0)
        if (dim > 0) Canvas(out).drawColor((dim * 255 / 100) shl 24)
        return out
    }
    fun apply(c: Context, f: File, title: String) {
        val bmp = render(c, f); val wm = WallpaperManager.getInstance(c)
        val home = WallpaperManager.FLAG_SYSTEM; val lock = WallpaperManager.FLAG_LOCK
        when (Cfg.s("target", "both")) {
            "home" -> wm.setBitmap(bmp, null, true, home)
            "lock" -> wm.setBitmap(bmp, null, true, lock)
            else -> wm.setBitmap(bmp, null, true, home or lock)
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
            var got: Pair<File, String>? = null
            if (online(c)) got = (if (Random.nextFloat() < 0.2f) pickFav(c) else null) ?: runCatching { fetchOne(c) }.getOrNull()
            val offline = got == null
            if (got == null) got = randomLocal(c)
            val g = got ?: return@withContext "Nothing to show yet — connect to the internet once"
            apply(c, g.first, g.second)
            if (!offline && libCount(c) < Cfg.i("libmax", 100) / 2) repeat(2) { runCatching { fetchOne(c) } }   // grow the offline library
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
