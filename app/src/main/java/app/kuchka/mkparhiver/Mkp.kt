package app.kuchka.mkparhiver

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * .mkh + .mkp format.
 * .mkh: 32-byte plain header 'mkp\0v100' | u32 BE headerSize | u32 BE dataSize | u32 BE count | 12 zero
 *       then count * 32 bytes XOR 0xA7: u32 BE offset | u32 BE size | u8 nameLen | name (zero padded to 23)
 * .mkp: files concatenated, each zero-padded to a multiple of 4, whole stream XOR 0xA7.
 */
object Mkp {
    const val HEADER = 32
    const val ENTRY = 32
    const val MAX_NAME = 23
    const val INDEX_NAME = "_index.txt"

    data class Entry(val name: String, val offset: Long, val size: Long)

    private fun xor(b: ByteArray, len: Int = b.size) {
        for (i in 0 until len) b[i] = (b[i].toInt() xor 0xA7).toByte()
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun readIndex(cr: ContentResolver, mkh: Uri): List<Entry> {
        val h = cr.openInputStream(mkh)?.use { it.readBytes() } ?: throw IOException("Cannot open .mkh")
        if (h.size < HEADER || h[0] != 'm'.code.toByte() || h[1] != 'k'.code.toByte() ||
            h[2] != 'p'.code.toByte() || h[3] != 0.toByte() || h[4] != 'v'.code.toByte()
        ) throw IOException("Not an .mkh file (bad signature)")
        val n = u32(h, 16)
        if (n > 1_000_000 || h.size < HEADER + n * ENTRY) throw IOException("Corrupted .mkh (entry table truncated)")
        val list = ArrayList<Entry>(n.toInt())
        val r = ByteArray(ENTRY)
        for (i in 0 until n.toInt()) {
            System.arraycopy(h, HEADER + i * ENTRY, r, 0, ENTRY)
            xor(r)
            val len = r[8].toInt() and 0xFF
            if (len > MAX_NAME) throw IOException("Corrupted .mkh (bad name length)")
            list += Entry(String(r, 9, len, Charsets.ISO_8859_1), u32(r, 0), u32(r, 4))
        }
        return list
    }

    private fun safeName(name: String) =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    fun extract(
        cr: ContentResolver, mkp: Uri, entries: List<Entry>, dest: DocumentFile,
        writeIndex: Boolean, onProgress: (Int, Int, String) -> Unit,
    ) {
        val pfd = cr.openFileDescriptor(mkp, "r") ?: throw IOException("Cannot open .mkp")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
            val ch = ins.channel
            val buf = ByteArray(64 * 1024)
            entries.forEachIndexed { i, e ->
                if (!safeName(e.name)) throw IOException("Unsafe file name: ${e.name}")
                onProgress(i, entries.size, e.name)
                dest.findFile(e.name)?.delete()
                val f = dest.createFile("application/octet-stream", e.name)
                    ?: throw IOException("Cannot create ${e.name}")
                val out = cr.openOutputStream(f.uri) ?: throw IOException("Cannot write ${e.name}")
                out.use { o ->
                    ch.position(e.offset)
                    var left = e.size
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw EOFException("Unexpected end of .mkp in ${e.name}")
                        xor(buf, n)
                        o.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        if (writeIndex) {
            dest.findFile(INDEX_NAME)?.delete()
            dest.createFile("text/plain", INDEX_NAME)?.let { f ->
                cr.openOutputStream(f.uri)?.use { it.write(entries.joinToString("\n") { e -> e.name }.toByteArray()) }
            }
        }
        onProgress(entries.size, entries.size, "")
    }

    fun pack(
        cr: ContentResolver, src: DocumentFile, mkh: Uri, mkp: Uri,
        onProgress: (Int, Int, String) -> Unit,
    ): Int {
        val files = src.listFiles()
            .filter { it.isFile && it.name != null && it.name != INDEX_NAME }
            .associateBy { it.name!! }
        if (files.isEmpty()) throw IOException("Folder has no files")

        val order = ArrayList<String>()
        val idxText = src.findFile(INDEX_NAME)?.let { f ->
            cr.openInputStream(f.uri)?.use { String(it.readBytes(), Charsets.ISO_8859_1) }
        }
        if (idxText != null) {
            idxText.split('\n').map { it.trim() }.filter { it.isNotEmpty() && it in files && it !in order }
                .forEach { order += it }
            files.keys.filter { it !in order }.sorted().forEach { order += it }
        } else {
            order += files.keys.sorted()
        }

        val n = order.size
        val table = ByteArray(n * ENTRY)
        val sizes = LongArray(n)
        var off = 0L
        order.forEachIndexed { i, nm ->
            if (nm.length > MAX_NAME || nm.any { it.code !in 32..126 })
                throw IOException("Name not allowed (ASCII, max $MAX_NAME chars): $nm")
            val size = files[nm]!!.length()
            sizes[i] = size
            val b = i * ENTRY
            putU32(table, b, off)
            putU32(table, b + 4, size)
            table[b + 8] = nm.length.toByte()
            for (k in nm.indices) table[b + 9 + k] = nm[k].code.toByte()
            off += size + ((4 - size % 4) % 4)
        }
        xor(table)

        val hdr = ByteArray(HEADER)
        "mkp\u0000v100".forEachIndexed { i, c -> hdr[i] = c.code.toByte() }
        putU32(hdr, 8, (HEADER + n * ENTRY).toLong())
        putU32(hdr, 12, off)
        putU32(hdr, 16, n.toLong())

        (cr.openOutputStream(mkh, "wt") ?: throw IOException("Cannot write .mkh")).use {
            it.write(hdr); it.write(table)
        }

        val buf = ByteArray(64 * 1024)
        BufferedOutputStream(cr.openOutputStream(mkp, "wt") ?: throw IOException("Cannot write .mkp")).use { out ->
            order.forEachIndexed { i, nm ->
                onProgress(i, n, nm)
                var written = 0L
                (cr.openInputStream(files[nm]!!.uri) ?: throw IOException("Cannot read $nm")).use { ins ->
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        xor(buf, r)
                        out.write(buf, 0, r)
                        written += r
                    }
                }
                if (written != sizes[i]) throw IOException("Size of $nm changed while packing")
                repeat(((4 - written % 4) % 4).toInt()) { out.write(0xA7) } // zero pad, XORed
            }
        }
        onProgress(n, n, "")
        return n
    }
}
