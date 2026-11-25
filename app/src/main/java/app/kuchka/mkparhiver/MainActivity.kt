package app.kuchka.mkparhiver

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.Executors

/** WinRAR / RAR-for-Android style UI: blue toolbar, file list with type badges, bottom action bar. */
class MainActivity : ComponentActivity() {

    private val primary = Color.parseColor("#2F3E8F")
    private val selectedBg = Color.parseColor("#E8EAF6")
    private val grey = Color.parseColor("#757575")

    private var mkh: Uri? = null
    private var mkp: Uri? = null
    private var entries: List<Mkp.Entry> = emptyList()
    private val checked = LinkedHashSet<Int>()
    private var baseName = "archive"
    private var packSrc: Uri? = null
    private var packMkh: Uri? = null
    private var pendingItems: List<Mkp.Entry> = emptyList()
    private var busy = false
    private var pendingMp3: List<Mkp.Entry> = emptyList()
    private var player: MediaPlayer? = null
    private var playingIdx = -1
    private var playToken = 0

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: ListView
    private lateinit var emptyView: View
    private val adapter = Adapter()
    private val io = Executors.newSingleThreadExecutor()

    // ---- activity result launchers -------------------------------------------------------

    private val openArchive = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val h = uris.firstOrNull { name(it).lowercase().endsWith(".mkh") }
        val p = uris.firstOrNull { name(it).lowercase().endsWith(".mkp") }
        if (h == null || p == null) {
            toast("Select both files: X.mkh and X.mkp")
            return@registerForActivityResult
        }
        mkh = h; mkp = p
        baseName = name(h).substringBeforeLast('.')
        loadIndex()
    }

    private val pickExtractDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) extractTo(uri)
    }

    private val pickMp3Dir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) exportMp3(uri)
    }

    private val pickPackDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            packSrc = uri
            baseName = DocumentFile.fromTreeUri(this, uri)?.name ?: "archive"
            createMkh.launch("$baseName.mkh")
        }
    }

    private val createMkh = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { packMkh = uri; createMkp.launch("$baseName.mkp") }
    }

    private val createMkp = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) startPack(uri)
    }

    // ---- UI ------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(primary)
            elevation = dp(4).toFloat()
            setPadding(dp(16), 0, dp(4), 0)
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitle = TextView(this).apply {
            setTextColor(Color.parseColor("#B3FFFFFF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titles.addView(title); titles.addView(subtitle)
        bar.addView(titles)
        bar.addView(barIcon(R.drawable.ic_open) { openArchiveClick() })
        bar.addView(barIcon(R.drawable.ic_select_all) { toggleSelectAll() })
        root.addView(bar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(Color.parseColor("#FFB300"))
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        // Content: list + empty state
        val content = FrameLayout(this)
        list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#E0E0E0"))
            dividerHeight = 1
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(pos) }
        }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_archive) })
            addView(TextView(context).apply {
                text = "No archive open"
                setTextColor(grey); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(20))
            })
            addView(flatButton("Open archive (.mkh + .mkp)", true) { openArchiveClick() })
            addView(flatButton("Create archive from folder", false) { pickPackDir.launch(null) })
        }
        content.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom action bar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        bottom.addView(navItem(R.drawable.ic_add, "Add") { pickPackDir.launch(null) })
        bottom.addView(navItem(R.drawable.ic_extract, "Extract to…") { extractClick() })
        bottom.addView(navItem(R.drawable.ic_music, "To MP3") { mp3Click() })
        bottom.addView(navItem(R.drawable.ic_select_all, "Select all") { toggleSelectAll() })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(60)))

        setContentView(root)
        refresh()
    }

    private fun barIcon(res: Int, click: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple()
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun navItem(res: Int, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = ripple()
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        addView(ImageView(context).apply {
            setImageResource(res); setColorFilter(primary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        addView(TextView(context).apply {
            text = label; setTextColor(primary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
        })
    }

    private fun flatButton(text: String, filled: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false
        setTextColor(if (filled) Color.WHITE else primary)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (filled) setColor(primary) else { setColor(Color.WHITE); setStroke(dp(1), primary) }
        }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(280), dp(48)).apply { topMargin = dp(8) }
    }

    private fun ripple() = TypedValue().let {
        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        getDrawable(it.resourceId)
    }

    // ---- list rows -----------------------------------------------------------------------

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        val badge = TextView(ctx)
        val nameView = TextView(ctx)
        val info = TextView(ctx)
        val box = CheckBox(ctx)
        val play = ImageView(ctx)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            badge.apply {
                gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTypeface(typeface, Typeface.BOLD)
                layoutParams = LayoutParams(dp(42), dp(42))
            }
            val col = LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) }
            }
            nameView.apply {
                setTextColor(Color.parseColor("#212121")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            info.apply { setTextColor(grey); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) }
            col.addView(nameView); col.addView(info)
            box.apply { isClickable = false; isFocusable = false; buttonTintList = ColorStateList.valueOf(primary) }
            play.apply {
                setColorFilter(primary); setPadding(dp(8), dp(8), dp(8), dp(8))
                isFocusable = false
                background = ripple()
                layoutParams = LayoutParams(dp(44), dp(44))
            }
            addView(badge); addView(col); addView(play); addView(box)
        }

        fun bind(e: Mkp.Entry, isChecked: Boolean, pos: Int) {
            val ext = e.name.substringAfterLast('.', "")
            badge.text = if (ext.isEmpty()) "FILE" else ext.uppercase().take(4)
            badge.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(extColor(ext)) }
            nameView.text = e.name
            info.text = human(e.size)
            box.isChecked = isChecked
            if (Snd.isAudio(e.name)) {
                play.visibility = View.VISIBLE
                play.setImageResource(if (playingIdx == pos) R.drawable.ic_pause else R.drawable.ic_play)
                play.setOnClickListener { togglePlay(pos) }
            } else {
                play.visibility = View.GONE
            }
            setBackgroundColor(if (isChecked) selectedBg else Color.WHITE)
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row(this@MainActivity)
            row.bind(entries[position], checked.contains(position), position)
            return row
        }
    }

    private fun extColor(ext: String): Int = Color.parseColor(
        when (ext.lowercase()) {
            "src", "txt", "ini", "cfg" -> "#5C6BC0"
            "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "#43A047"
            "ogg", "wav", "mp3", "mid" -> "#FB8C00"
            "mp4", "avi", "mkv" -> "#E53935"
            "mkh", "mkp", "zip", "rar" -> "#8E24AA"
            else -> "#78909C"
        }
    )

    // ---- actions -------------------------------------------------------------------------

    private fun openArchiveClick() { if (!busy) openArchive.launch(arrayOf("*/*")) }

    private fun toggle(pos: Int) {
        if (!checked.remove(pos)) checked.add(pos)
        adapter.notifyDataSetChanged(); refresh()
    }

    private fun toggleSelectAll() {
        if (entries.isEmpty()) return toast("Open an archive first")
        if (checked.size == entries.size) checked.clear() else { checked.clear(); checked.addAll(entries.indices) }
        adapter.notifyDataSetChanged(); refresh()
    }

    private fun extractClick() {
        if (busy) return
        if (mkh == null || mkp == null || entries.isEmpty()) return toast("Open an archive first")
        pendingItems = if (checked.isEmpty()) entries else checked.sorted().map { entries[it] }
        pickExtractDir.launch(null)
    }

    private fun refresh() {
        val has = entries.isNotEmpty()
        emptyView.visibility = if (has) View.GONE else View.VISIBLE
        list.visibility = if (has) View.VISIBLE else View.GONE
        title.text = if (has) "$baseName.mkp" else "MKP Arhiver"
        if (busy) return
        subtitle.text = when {
            playingIdx >= 0 && playingIdx < entries.size -> "Playing: ${entries[playingIdx].name}"
            !has -> "Archiver for .mkh / .mkp"
            checked.isEmpty() -> "${entries.size} files · ${human(entries.sumOf { it.size })}"
            else -> "${checked.size} selected · ${human(checked.sumOf { entries[it].size })}"
        }
    }

    // ---- playback ------------------------------------------------------------------------

    private fun stopPlayback() {
        playToken++
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        playingIdx = -1
        adapter.notifyDataSetChanged(); refresh()
    }

    private fun togglePlay(pos: Int) {
        if (busy) return
        if (playingIdx == pos) return stopPlayback()
        stopPlayback()
        val e = entries[pos]
        val token = playToken
        subtitle.text = "Loading ${e.name}…"
        io.execute {
            try {
                val f = File(cacheDir, "play.ogg")
                Mkp.extractToFile(contentResolver, mkp!!, e, f)
                ui {
                    if (token != playToken) return@ui
                    val mp = MediaPlayer()
                    player = mp
                    mp.setOnPreparedListener {
                        if (token == playToken) { it.start(); playingIdx = pos; adapter.notifyDataSetChanged(); refresh() }
                    }
                    mp.setOnCompletionListener { if (token == playToken) stopPlayback() }
                    mp.setOnErrorListener { _, _, _ ->
                        if (token == playToken) { stopPlayback(); toast("Cannot play ${e.name}", true) }
                        true
                    }
                    try { mp.setDataSource(f.path); mp.prepareAsync() }
                    catch (ex: Exception) { stopPlayback(); toast("Cannot play: ${ex.message}", true) }
                }
            } catch (ex: Exception) {
                ui { refresh(); toast("Error: ${ex.message}", true) }
            }
        }
    }

    // ---- MP3 export ----------------------------------------------------------------------

    private fun mp3Click() {
        if (busy) return
        if (mkh == null || mkp == null || entries.isEmpty()) return toast("Open an archive first")
        val base = if (checked.isEmpty()) entries else checked.sorted().map { entries[it] }
        pendingMp3 = base.filter { Snd.isAudio(it.name) }
        if (pendingMp3.isEmpty()) return toast("No .snd / .ogg files selected")
        pickMp3Dir.launch(null)
    }

    private fun exportMp3(tree: Uri) {
        val dest = DocumentFile.fromTreeUri(this, tree) ?: return toast("Cannot open folder")
        val items = pendingMp3
        stopPlayback()
        busy = true
        io.execute {
            try {
                items.forEachIndexed { i, e ->
                    reportProgress("Converting", i, items.size, e.name)
                    val ogg = File(cacheDir, "conv.ogg")
                    val wav = File(cacheDir, "conv.wav")
                    val mp3 = File(cacheDir, "conv.mp3")
                    try {
                        mp3.delete()
                        Mkp.extractToFile(contentResolver, mkp!!, e, ogg)
                        val rate = Snd.decodeToWav(ogg, wav)
                        Snd.wavToMp3(wav, mp3, rate)
                        val outName = e.name.substringBeforeLast('.') + ".mp3"
                        dest.findFile(outName)?.delete()
                        val df = dest.createFile("audio/mpeg", outName) ?: throw java.io.IOException("Cannot create $outName")
                        contentResolver.openOutputStream(df.uri)?.use { o -> mp3.inputStream().use { it.copyTo(o) } }
                    } finally {
                        ogg.delete(); wav.delete(); mp3.delete()
                    }
                }
                finish("Converted ${items.size} file(s) to MP3")
            } catch (ex: Exception) {
                finish("Error: ${ex.message}", true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        io.shutdown()
    }

    private fun loadIndex() {
        stopPlayback()
        busy = true; subtitle.text = "Reading…"
        io.execute {
            try {
                val e = Mkp.readIndex(contentResolver, mkh!!)
                ui { entries = e; checked.clear(); busy = false; adapter.notifyDataSetChanged(); refresh() }
            } catch (ex: Exception) {
                ui { entries = emptyList(); checked.clear(); busy = false; adapter.notifyDataSetChanged(); refresh(); toast("Error: ${ex.message}", true) }
            }
        }
    }

    private fun extractTo(tree: Uri) {
        val dest = DocumentFile.fromTreeUri(this, tree) ?: return toast("Cannot open folder")
        val items = pendingItems
        val all = items.size == entries.size
        busy = true
        io.execute {
            try {
                Mkp.extract(contentResolver, mkp!!, items, dest, all) { i, n, nm -> reportProgress("Extracting", i, n, nm) }
                finish("Extracted ${items.size} files")
            } catch (ex: Exception) {
                finish("Error: ${ex.message}", true)
            }
        }
    }

    private fun startPack(mkpUri: Uri) {
        val src = DocumentFile.fromTreeUri(this, packSrc!!) ?: return toast("Cannot open folder")
        val h = packMkh!!
        busy = true
        io.execute {
            try {
                val n = Mkp.pack(contentResolver, src, h, mkpUri) { i, total, nm -> reportProgress("Packing", i, total, nm) }
                finish("Archive created: $n files")
            } catch (ex: Exception) {
                finish("Error: ${ex.message}", true)
            }
        }
    }

    private fun reportProgress(what: String, i: Int, n: Int, nm: String) = ui {
        progress.visibility = View.VISIBLE
        subtitle.text = if (nm.isEmpty()) what else "$what $nm (${i + 1}/$n)"
        progress.progress = if (n == 0) 0 else i * 100 / n
    }

    private fun finish(msg: String, long: Boolean = false) = ui {
        busy = false
        progress.visibility = View.INVISIBLE
        refresh()
        toast(msg, long)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun name(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) c.getString(col) else null
        } ?: (uri.lastPathSegment ?: "")

    private fun human(b: Long): String = when {
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun toast(s: String, long: Boolean = false) =
        Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
