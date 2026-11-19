package com.clipforge.export

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Guarantees "+faststart": if the moov box comes after mdat, rewrites the file with moov first and
 * shifts every chunk offset (stco/co64) by moov's size. Same idea as ffmpeg's qt-faststart.
 */
object Mp4FastStart {
    data class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    fun topLevelBoxes(f: RandomAccessFile): List<Box> {
        val out = ArrayList<Box>()
        var pos = 0L
        val len = f.length()
        val hdr = ByteArray(16)
        while (pos + 8 <= len) {
            f.seek(pos)
            f.readFully(hdr, 0, 8)
            var size = u32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                f.readFully(hdr, 8, 8)
                size = ByteBuffer.wrap(hdr, 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < header || pos + size > len) throw IllegalStateException("Corrupt MP4 box '$type' at $pos")
            out += Box(type, pos, size, header)
            pos += size
        }
        return out
    }

    fun isFastStart(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
        val boxes = topLevelBoxes(f)
        val moov = boxes.indexOfFirst { it.type == "moov" }
        val mdat = boxes.indexOfFirst { it.type == "mdat" }
        moov >= 0 && (mdat < 0 || moov < mdat)
    }

    /** @return true if [src] already was fast-start (nothing written) or [dst] was written. */
    fun ensure(src: File, dst: File): Boolean {
        RandomAccessFile(src, "r").use { f ->
            val boxes = topLevelBoxes(f)
            val moovIdx = boxes.indexOfFirst { it.type == "moov" }
            val mdatIdx = boxes.indexOfFirst { it.type == "mdat" }
            require(moovIdx >= 0) { "No moov box" }
            if (mdatIdx < 0 || moovIdx < mdatIdx) return true
            val moovBox = boxes[moovIdx]
            require(moovBox.size < Int.MAX_VALUE) { "moov too large" }
            val moov = ByteArray(moovBox.size.toInt())
            f.seek(moovBox.offset)
            f.readFully(moov)
            // Everything from the first mdat onward moves forward by moov's size.
            val firstMovedOffset = boxes[mdatIdx].offset
            patchChunkOffsets(moov, 0, moov.size, moovBox.size, firstMovedOffset)

            RandomAccessFile(dst, "rw").use { out ->
                out.setLength(0)
                val buf = ByteArray(1 shl 16)
                fun copy(b: Box) {
                    f.seek(b.offset)
                    var left = b.size
                    while (left > 0) {
                        val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IllegalStateException("Unexpected EOF")
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                boxes.subList(0, mdatIdx).forEach { if (it.type != "moov") copy(it) }
                out.write(moov)
                boxes.subList(mdatIdx, boxes.size).forEach { if (it.type != "moov") copy(it) }
            }
            return false
        }
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

    private fun patchChunkOffsets(b: ByteArray, start: Int, end: Int, delta: Long, minOffset: Long) {
        var pos = start
        while (pos + 8 <= end) {
            var size = u32(b, pos)
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = ByteBuffer.wrap(b, pos + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) throw IllegalStateException("Corrupt box '$type' inside moov")
            val body = pos + header
            when {
                type == "moov" && pos == start -> patchChunkOffsets(b, body, (pos + size).toInt(), delta, minOffset)
                type in containers -> patchChunkOffsets(b, body, (pos + size).toInt(), delta, minOffset)
                type == "stco" -> {
                    val count = u32(b, body + 4).toInt()
                    for (i in 0 until count) {
                        val p = body + 8 + i * 4
                        val v = u32(b, p)
                        val nv = if (v >= minOffset) v + delta else v
                        if (nv > 0xFFFFFFFFL) throw IllegalStateException("Chunk offset overflow; file too large for stco")
                        putU32(b, p, nv)
                    }
                }
                type == "co64" -> {
                    val count = u32(b, body + 4).toInt()
                    val bb = ByteBuffer.wrap(b)
                    for (i in 0 until count) {
                        val p = body + 8 + i * 8
                        val v = bb.getLong(p)
                        if (v >= minOffset) bb.putLong(p, v + delta)
                    }
                }
            }
            pos += size.toInt()
        }
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }
}
