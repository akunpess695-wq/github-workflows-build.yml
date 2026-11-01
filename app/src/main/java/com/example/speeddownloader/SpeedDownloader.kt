package com.example.speeddownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// ===== Models.kt =====
enum class Status { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }

class Segment(val start: Long, val end: Long, d: Long = 0) {
    val downloaded = AtomicLong(d)
    @Volatile var done = false
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val finished: Boolean get() = done || (end >= 0 && downloaded.get() >= length)
}

class Item(val id: String, val url: String, @Volatile var fileName: String) {
    @Volatile var total: Long = -1
    @Volatile var status: Status = Status.QUEUED
    @Volatile var error: String = ""
    @Volatile var speed: Long = 0
    @Volatile var rangeOk: Boolean = false
    @Volatile var savedUri: String = ""
    @Volatile var segments: List<Segment> = emptyList()
    @Volatile var lastBytes: Long = 0
    @Volatile var job: Job? = null

    fun downloaded(): Long = segments.sumOf { it.downloaded.get() }

    fun percent(): Int =
        if (total > 0) ((downloaded() * 100) / total).toInt().coerceIn(0, 100) else 0
}

// ===== Util.kt =====
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    var v = b.toDouble()
    var i = -1
    do {
        v /= 1024.0
        i++
    } while (v >= 1024.0 && i < 2)
    return String.format(Locale.US, "%.1f %s", v, arrayOf("KB", "MB", "GB")[i])
}

// ===== App.kt =====
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadManager.setup(this)
    }
}

// ===== DownloadManager.kt =====
object DownloadManager {
    private const val MAX_ACTIVE = 2
    private const val MAX_SEGMENTS = 8
    private const val MIN_SEGMENT = 1024L * 1024L
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val items = CopyOnWriteArrayList<Item>()

    private lateinit var ctx: Context
    private var ready = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val store: File get() = File(ctx.filesDir, "downloads.json")

    @Synchronized
    fun setup(c: Context) {
        if (ready) return
        ctx = c.applicationContext
        ready = true
        load()
        scope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    // ---------- aksi dari UI ----------

    fun add(url: String): Item {
        val item = Item(UUID.randomUUID().toString(), url, guessName(url))
        items.add(0, item)
        save()
        pump()
        return item
    }

    fun pause(item: Item) {
        if (item.status == Status.DOWNLOADING || item.status == Status.QUEUED) {
            item.status = Status.PAUSED
            item.job?.cancel()
            save()
            pump()
        }
    }

    fun resume(item: Item) {
        if (item.status == Status.PAUSED || item.status == Status.ERROR) {
            item.status = Status.QUEUED
            item.error = ""
            save()
            pump()
        }
    }

    fun remove(item: Item) {
        item.status = Status.PAUSED
        item.job?.cancel()
        if (item.savedUri.isEmpty()) tmpFile(item).delete()
        items.remove(item)
        save()
        pump()
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ---------- antrean ----------

    @Synchronized
    private fun pump() {
        var active = items.count { it.status == Status.DOWNLOADING }
        for (item in items.toList().asReversed()) {
            if (active >= MAX_ACTIVE) break
            if (item.status == Status.QUEUED) {
                start(item)
                active++
            }
        }
    }

    private fun start(item: Item) {
        item.status = Status.DOWNLOADING
        item.error = ""
        item.lastBytes = item.downloaded()
        DownloadService.start(ctx)
        item.job = scope.launch { execute(item) }
    }

    private fun tick() {
        var any = false
        for (i in items) {
            val d = i.downloaded()
            i.speed = if (i.status == Status.DOWNLOADING) (d - i.lastBytes).coerceAtLeast(0) else 0
            i.lastBytes = d
            if (i.status == Status.DOWNLOADING) any = true
        }
        if (any) save()
    }

    // ---------- proses unduhan ----------

    private suspend fun execute(item: Item) {
        try {
            if (item.segments.isEmpty()) probe(item)
            coroutineScope {
                item.segments.filter { !it.finished }
                    .map { seg -> async { downloadSegment(item, seg) } }
                    .awaitAll()
            }
            if (item.total <= 0) item.total = item.downloaded()
            currentCoroutineContext().ensureActive()
            finish(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (item.status == Status.DOWNLOADING) {
                item.status = Status.ERROR
                item.error = e.message ?: "Gagal"
            }
        } finally {
            save()
            pump()
        }
    }

    // Cek ukuran file dan dukungan Range, lalu bagi menjadi segmen
    private fun probe(item: Item) {
        val req = Request.Builder().url(item.url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server menjawab ${r.code}")
            val name = nameFromDisposition(r.header("Content-Disposition"))
                ?: guessName(r.request.url.toString())
            item.fileName = sanitize(name)
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                item.total = total
                item.rangeOk = total > 0
            } else {
                item.total = r.body?.contentLength() ?: -1L
                item.rangeOk = false
            }
        }

        val segs = ArrayList<Segment>()
        if (item.rangeOk) {
            val n = minOf(MAX_SEGMENTS.toLong(), maxOf(1L, item.total / MIN_SEGMENT)).toInt()
            val chunk = item.total / n
            for (i in 0 until n) {
                val s = i * chunk
                val e = if (i == n - 1) item.total - 1 else s + chunk - 1
                segs.add(Segment(s, e))
            }
        } else {
            segs.add(Segment(0, if (item.total > 0) item.total - 1 else -1))
        }
        item.segments = segs

        RandomAccessFile(tmpFile(item), "rw").use { raf ->
            if (item.total > 0) raf.setLength(item.total)
        }
    }

    private suspend fun downloadSegment(item: Item, seg: Segment) {
        var attempt = 0
        while (true) {
            try {
                fetch(item, seg)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt > 4) throw e
                delay(1500L * attempt)
            }
        }
    }

    private suspend fun fetch(item: Item, seg: Segment) {
        val rb = Request.Builder().url(item.url).header("User-Agent", UA)
        val startPos: Long
        if (item.rangeOk) {
            startPos = seg.start + seg.downloaded.get()
            rb.header("Range", "bytes=$startPos-${seg.end}")
        } else {
            // server tidak mendukung lanjut: mulai ulang dari awal
            seg.downloaded.set(0)
            startPos = 0L
        }

        val call = client.newCall(rb.build())
        val handle = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (item.rangeOk && r.code != 206) throw IOException("Server tidak mendukung Range (${r.code})")
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("Respons kosong")
                RandomAccessFile(tmpFile(item), "rw").use { raf ->
                    raf.seek(startPos)
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            seg.downloaded.addAndGet(n.toLong())
                        }
                    }
                }
            }
        } finally {
            handle.dispose()
        }
        if (seg.end >= 0 && seg.downloaded.get() < seg.length) throw IOException("Koneksi terputus")
        seg.done = true
    }

    // Salin hasil ke folder Download/SpeedDownloader
    private fun finish(item: Item) {
        val tmp = tmpFile(item)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(item.fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpeedDownloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal menyimpan ke folder Download")
        val out = resolver.openOutputStream(uri) ?: throw IOException("Gagal membuka file tujuan")
        out.use { o -> tmp.inputStream().use { it.copyTo(o, 256 * 1024) } }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        tmp.delete()
        item.savedUri = uri.toString()
        item.status = Status.DONE
    }

    private fun tmpFile(item: Item): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tmp_${item.id}.part")

    // ---------- nama file ----------

    private fun guessName(url: String): String {
        val last = Uri.parse(url).lastPathSegment ?: ""
        val name = Uri.decode(last).substringAfterLast('/')
        return sanitize(if (name.isBlank()) "file_${System.currentTimeMillis()}" else name)
    }

    private fun sanitize(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    private fun nameFromDisposition(cd: String?): String? {
        if (cd == null) return null
        Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(cd)?.let { return Uri.decode(it.groupValues[1]) }
        Regex("filename=\"?([^\";]+)\"?").find(cd)?.let { return it.groupValues[1] }
        return null
    }

    // ---------- penyimpanan daftar ----------

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            for (i in items) {
                val segs = JSONArray()
                for (s in i.segments) {
                    segs.put(
                        JSONObject().put("s", s.start).put("e", s.end)
                            .put("d", s.downloaded.get()).put("x", s.done)
                    )
                }
                arr.put(
                    JSONObject().put("id", i.id).put("url", i.url).put("name", i.fileName)
                        .put("total", i.total).put("status", i.status.name).put("range", i.rangeOk)
                        .put("uri", i.savedUri).put("err", i.error).put("segs", segs)
                )
            }
            store.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!store.exists()) return
            val arr = JSONArray(store.readText())
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                val item = Item(o.getString("id"), o.getString("url"), o.getString("name"))
                item.total = o.optLong("total", -1)
                val st = Status.valueOf(o.optString("status", "PAUSED"))
                item.status = if (st == Status.DOWNLOADING || st == Status.QUEUED) Status.PAUSED else st
                item.rangeOk = o.optBoolean("range", false)
                item.savedUri = o.optString("uri", "")
                item.error = o.optString("err", "")
                val sa = o.optJSONArray("segs")
                val segs = ArrayList<Segment>()
                if (sa != null) {
                    for (k in 0 until sa.length()) {
                        val s = sa.getJSONObject(k)
                        val seg = Segment(s.getLong("s"), s.getLong("e"), s.getLong("d"))
                        seg.done = s.optBoolean("x", false)
                        segs.add(seg)
                    }
                }
                item.segments = segs
                items.add(item)
            }
        } catch (_: Exception) {
        }
    }
}

// ===== DownloadService.kt =====
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Unduhan", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NID, build("Menyiapkan unduhan…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    val act = DownloadManager.items.filter {
                        it.status == Status.DOWNLOADING || it.status == Status.QUEUED
                    }
                    if (act.isEmpty()) {
                        stopSelf()
                        break
                    }
                    val speed = act.sumOf { it.speed }
                    val pct = act.map { it.percent() }.average().toInt()
                    nm.notify(NID, build("${act.size} unduhan • ${fmtSize(speed)}/dtk", pct))
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun build(text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SpeedDownloader")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CH = "unduhan"
        private const val NID = 1

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

// ===== MainActivity.kt =====
class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private val adapter = DlAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        input = EditText(this).apply {
            hint = "Tempel tautan unduhan"
            setSingleLine()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val go = Button(this).apply { text = "Unduh" }
        val pintas = Button(this).apply { text = "Jalan Pintas" }
        row.addView(go, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(pintas, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        top.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        go.setOnClickListener { startDownload(input.text.toString()) }
        pintas.setOnClickListener { openPintas(input.text.toString()) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    adapter.submit(DownloadManager.items.toList())
                    delay(500)
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Tautan dari menu Bagikan
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val raw = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(raw)?.value ?: return
        i.action = null
        AlertDialog.Builder(this)
            .setTitle("Tautan diterima")
            .setItems(arrayOf("Unduh langsung", "Urai dulu (Jalan Pintas)")) { _, which ->
                if (which == 0) startDownload(url) else openPintas(url)
            }
            .show()
    }

    private fun startDownload(raw: String) {
        val url = Regex("https?://\\S+").find(raw.trim())?.value
        if (url == null) {
            Toast.makeText(this, "Tautan tidak valid", Toast.LENGTH_SHORT).show()
            return
        }
        DownloadManager.add(url)
        input.setText("")
    }

    private fun openPintas(raw: String) {
        startActivity(Intent(this, PintasActivity::class.java).putExtra("url", raw.trim()))
    }
}

class DlAdapter : RecyclerView.Adapter<DlAdapter.VH>() {

    private var data: List<Item> = emptyList()

    class VH(
        val root: LinearLayout,
        val title: TextView,
        val bar: ProgressBar,
        val info: TextView,
        val act: Button,
        val del: Button
    ) : RecyclerView.ViewHolder(root)

    fun submit(list: List<Item>) {
        val same = list.size == data.size && list.indices.all { list[it].id == data[it].id }
        data = list
        if (same) notifyItemRangeChanged(0, list.size) else notifyDataSetChanged()
    }

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val title = TextView(c).apply { textSize = 16f }
        val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val info = TextView(c).apply { textSize = 13f }
        val buttons = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val act = Button(c)
        val del = Button(c).apply { text = "Hapus" }
        buttons.addView(act, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(del, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return VH(root, title, bar, info, act, del)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = data[position]
        val ctx = h.root.context
        val pct = item.percent()
        val done = item.downloaded()
        val totalText = if (item.total > 0) fmtSize(item.total) else "?"

        h.title.text = item.fileName
        h.bar.progress = if (item.status == Status.DONE) 100 else pct
        h.info.text = when (item.status) {
            Status.DOWNLOADING ->
                if (item.total > 0 && done >= item.total) "Menyimpan ke folder Download…"
                else "$pct% • ${fmtSize(done)} / $totalText • ${fmtSize(item.speed)}/dtk"
            Status.QUEUED -> "Antre…"
            Status.PAUSED -> "Dijeda • $pct% (${fmtSize(done)})"
            Status.DONE -> "Selesai • $totalText • Download/SpeedDownloader"
            Status.ERROR -> "Gagal: ${item.error}"
        }
        h.act.text = when (item.status) {
            Status.DOWNLOADING, Status.QUEUED -> "Jeda"
            Status.PAUSED, Status.ERROR -> "Lanjut"
            Status.DONE -> "Buka"
        }
        h.act.setOnClickListener {
            when (item.status) {
                Status.DOWNLOADING, Status.QUEUED -> DownloadManager.pause(item)
                Status.PAUSED, Status.ERROR -> DownloadManager.resume(item)
                Status.DONE -> {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(item.savedUri), DownloadManager.mimeOf(item.fileName))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(i)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Tidak ada aplikasi untuk membuka file ini", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        h.del.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Hapus dari daftar?")
                .setMessage(
                    if (item.status == Status.DONE) "File yang sudah tersimpan di folder Download tidak ikut terhapus."
                    else "Unduhan dibatalkan dan data sementaranya dihapus."
                )
                .setPositiveButton("Hapus") { _, _ -> DownloadManager.remove(item) }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}

// ===== PintasActivity.kt =====
// Jalan Pintas: urai halaman penuh iklan, tampilkan tautan tujuan
class PintasActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private val found = LinkedHashSet<String>()

    // Pemindai: cari tautan tujuan di HTML (termasuk yang disandikan base64)
    private val scanJs = """
        (function(){
          var t=/(shopee|shope\.ee|mediafire|drive\.google|mega\.nz|dropbox|github\.com|apkpure)/i;
          var f=/\.(apk|zip|rar|7z|mp4|mkv|mp3|pdf|iso|exe)\b/i;
          var s=document.documentElement.outerHTML;
          (s.match(/https?:\/\/[^\s"'<>\\]+/g)||[]).forEach(function(u){ if(t.test(u)||f.test(u)) App.report(u); });
          (s.match(/aHR0c[A-Za-z0-9+\/=]{10,}/g)||[]).forEach(function(b){
            try{ var u=atob(b); if(/^https?:/.test(u)) App.report(u); }catch(e){}
          });
        })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface
        fun report(url: String) = runOnUiThread { addLink(url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        input = EditText(this).apply { hint = "Tempel tautan halaman iklan"; setSingleLine() }
        val go = Button(this).apply { text = "Cari tautan tujuan" }
        web = WebView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xFF111111.toInt())
        }
        val scroll = ScrollView(this).apply { addView(list) }

        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(go, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.6f))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "App")
        // Halaman yang memicu unduhan file langsung ditangkap sebagai tautan tujuan
        web.setDownloadListener { url, _, _, _, _ -> runOnUiThread { addLink(url) } }
        web.webViewClient = object : WebViewClient() {
            // Pengalihan otomatis (tanpa sentuhan pengguna) diblokir, tujuannya dicatat
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.isForMainFrame && !r.hasGesture()) {
                    addLink(r.url.toString())
                    return true
                }
                return false
            }

            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript(scanJs, null)
            }
        }

        go.setOnClickListener { open(input.text.toString()) }

        val start = intent.getStringExtra("url").orEmpty()
        if (start.isNotBlank()) {
            input.setText(start)
            open(start)
        }
    }

    private fun open(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        found.clear()
        list.removeAllViews()
        web.loadUrl(u)
    }

    private fun addLink(url: String) {
        if (!found.add(url)) return
        list.addView(TextView(this).apply {
            text = url
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 14f
            setPadding(0, 12, 0, 12)
            setOnClickListener {
                AlertDialog.Builder(this@PintasActivity)
                    .setTitle(url.take(60))
                    .setItems(arrayOf("Unduh", "Buka di browser", "Salin")) { _, which ->
                        when (which) {
                            0 -> {
                                DownloadManager.add(url)
                                Toast.makeText(this@PintasActivity, "Ditambahkan ke antrean", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            1 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            else -> {
                                val cm = getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("tautan", url))
                                Toast.makeText(this@PintasActivity, "Tautan disalin", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        })
    }
}
