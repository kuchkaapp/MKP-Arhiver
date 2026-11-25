package app.kuchka.mkparhiver

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException

/**
 * .snd = plain Ogg Vorbis stream (no XOR, no extra header), so Android's MediaPlayer plays it as is.
 * MP3 export: Vorbis -> PCM (MediaCodec) -> WAV -> LAME (Mp3Converter library).
 */
object Snd {
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it == "snd" || it == "ogg" }

    /** Decodes [src] to a 16-bit stereo WAV. Returns the sample rate. */
    fun decodeToWav(src: File, out: File): Int {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) { ex.release(); throw IOException("No audio track") }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var dataBytes = 0L
        try {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.size > 0) {
                            val arr = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(arr)
                            if (channels == 1) { // upmix mono -> stereo
                                val st = ByteArray(arr.size * 2)
                                var k = 0
                                while (k + 1 < arr.size) {
                                    st[2 * k] = arr[k]; st[2 * k + 1] = arr[k + 1]
                                    st[2 * k + 2] = arr[k]; st[2 * k + 3] = arr[k + 1]
                                    k += 2
                                }
                                raf.write(st); dataBytes += st.size
                            } else {
                                raf.write(arr); dataBytes += arr.size
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
                // WAV header
                raf.seek(0)
                val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
                h.putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                h.put("data".toByteArray()).putInt(dataBytes.toInt())
                raf.write(h.array())
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        if (dataBytes == 0L) throw IOException("Decoder produced no audio")
        return rate
    }

    private fun converterClass(): Class<*> {
        val names = listOf(
            "com.jaygoo.library.converter.Mp3Converter",
            "com.jaygoo.mp3converter.Mp3Converter",
            "com.jaygoo.library.Mp3Converter",
        )
        for (n in names) try { return Class.forName(n) } catch (_: ClassNotFoundException) {}
        throw IOException("MP3 encoder (Mp3Converter) class not found")
    }

    fun wavToMp3(wav: File, mp3: File, rate: Int, kbps: Int = 192) {
        val c = converterClass()
        val i = Int::class.javaPrimitiveType!!
        try {
            c.getMethod("init", i, i, i, i, i, i).invoke(null, rate, 2, 0, rate, kbps, 5)
            c.getMethod("convertMp3", String::class.java, String::class.java).invoke(null, wav.path, mp3.path)
        } catch (e: InvocationTargetException) {
            throw IOException("MP3 encoding failed: ${e.targetException?.message}")
        }
        if (!mp3.exists() || mp3.length() == 0L) throw IOException("MP3 encoding produced no output")
    }
}
