package app.kuchka.mkparhiver

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var mkh: Uri? = null
    private var mkp: Uri? = null
    private var entries: List<Mkp.Entry> = emptyList()
    private var baseName = "archive"
    private var packSrc: Uri? = null
    private var packMkh: Uri? = null
    private var extractSelectedOnly = false

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val io = Executors.newSingleThreadExecutor()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val row1 = LinearLayout(this).apply {
            addView(button("Open .mkh+.mkp") { openArchive.launch(arrayOf("*/*")) })
            addView(button("Create archive") { pickPackDir.launch(null) })
        }
        val row2 = LinearLayout(this).apply {
            addView(button("Extract all") {
                if (needArchive()) { extractSelectedOnly = false; pickExtractDir.launch(null) }
            })
            addView(button("Extract selected") {
                if (needArchive()) {
                    if (selected().isEmpty()) toast("Tick files in the list first")
                    else { extractSelectedOnly = true; pickExtractDir.launch(null) }
                }
            })
        }
        status = TextView(this).apply {
            text = "Open an archive (select both .mkh and .mkp) or create a new one from a folder."
            setPadding(0, dp(8), 0, dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(row1); root.addView(row2); root.addView(status); root.addView(progress); root.addView(list)
        setContentView(root)
    }

    private fun needArchive(): Boolean {
        if (mkh == null || mkp == null || entries.isEmpty()) { toast("Open an archive first"); return false }
        return true
    }

    private fun selected(): List<Mkp.Entry> {
        val sp = list.checkedItemPositions
        return entries.filterIndexed { i, _ -> sp.get(i) }
    }

    private fun loadIndex() {
        status.text = "Reading…"
        io.execute {
            try {
                val e = Mkp.readIndex(contentResolver, mkh!!)
                ui {
                    entries = e
                    list.adapter = ArrayAdapter(
                        this, android.R.layout.simple_list_item_multiple_choice,
                        e.map { "${it.name}   (${human(it.size)})" }
                    )
                    status.text = "$baseName: ${e.size} files, ${human(e.sumOf { it.size })}"
                    progress.progress = 0
                }
            } catch (ex: Exception) {
                ui { status.text = "Error: ${ex.message}"; entries = emptyList() }
            }
        }
    }

    private fun extractTo(tree: Uri) {
        val dest = DocumentFile.fromTreeUri(this, tree) ?: return toast("Cannot open folder")
        val items = if (extractSelectedOnly) selected() else entries
        val all = !extractSelectedOnly
        io.execute {
            try {
                Mkp.extract(contentResolver, mkp!!, items, dest, all) { i, n, nm -> reportProgress("Extracting", i, n, nm) }
                ui { status.text = "Done: extracted ${items.size} files"; progress.progress = 100 }
            } catch (ex: Exception) {
                ui { status.text = "Error: ${ex.message}" }
            }
        }
    }

    private fun startPack(mkpUri: Uri) {
        val src = DocumentFile.fromTreeUri(this, packSrc!!) ?: return toast("Cannot open folder")
        val h = packMkh!!
        io.execute {
            try {
                val n = Mkp.pack(contentResolver, src, h, mkpUri) { i, total, nm -> reportProgress("Packing", i, total, nm) }
                ui { status.text = "Done: packed $n files"; progress.progress = 100 }
            } catch (ex: Exception) {
                ui { status.text = "Error: ${ex.message}" }
            }
        }
    }

    private fun reportProgress(what: String, i: Int, n: Int, nm: String) = ui {
        status.text = if (nm.isEmpty()) what else "$what $nm (${i + 1}/$n)"
        progress.progress = if (n == 0) 0 else i * 100 / n
    }

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
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
