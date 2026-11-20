package app.htmlbuilder

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rewrites the string pool of a compiled (binary) AndroidManifest.xml. */
object ManifestPatcher {

    fun patch(m: ByteArray, oldPkg: String, newPkg: String, oldLabel: String, newLabel: String): ByteArray {
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        val ps = 8
        require(b.getShort(0).toInt() == 3 && b.getShort(ps).toInt() == 1) { "Unexpected manifest format" }
        val hs = b.getShort(ps + 2).toInt() and 0xFFFF
        val size = b.getInt(ps + 4)
        val sc = b.getInt(ps + 8)
        val yc = b.getInt(ps + 12)
        val flags = b.getInt(ps + 16)
        val sStart = b.getInt(ps + 20)
        val utf8 = (flags and 0x100) != 0

        val strings = ArrayList<String>(sc)
        for (i in 0 until sc) {
            var p = ps + sStart + b.getInt(ps + hs + i * 4)
            if (utf8) {
                val c = m[p++].toInt() and 0xFF
                if ((c and 0x80) != 0) p++
                var n = m[p++].toInt() and 0xFF
                if ((n and 0x80) != 0) n = ((n and 0x7F) shl 8) or (m[p++].toInt() and 0xFF)
                strings.add(String(m, p, n, Charsets.UTF_8))
            } else {
                var n = b.getShort(p).toInt() and 0xFFFF
                p += 2
                if ((n and 0x8000) != 0) {
                    n = ((n and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF)
                    p += 2
                }
                strings.add(String(m, p, n * 2, Charsets.UTF_16LE))
            }
        }

        val data = ByteArrayOutputStream()
        val offs = IntArray(sc)
        for (i in 0 until sc) {
            val s = strings[i].let {
                when {
                    it == oldPkg || it.startsWith("$oldPkg.") -> newPkg + it.substring(oldPkg.length)
                    it == oldLabel -> newLabel
                    else -> it
                }
            }
            offs[i] = data.size()
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                len8(data, s.length); len8(data, bytes.size)
                data.write(bytes); data.write(0)
            } else {
                if (s.length > 0x7FFF) {
                    le16(data, (s.length shr 16) or 0x8000); le16(data, s.length and 0xFFFF)
                } else le16(data, s.length)
                data.write(s.toByteArray(Charsets.UTF_16LE)); le16(data, 0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)

        val yStart = b.getInt(ps + 24)
        val styleBytes = if (yc > 0) m.copyOfRange(ps + yStart, ps + size) else ByteArray(0)
        val newSStart = hs + sc * 4 + yc * 4
        val newYStart = if (yc > 0) newSStart + data.size() else 0
        val poolSize = newSStart + data.size() + styleBytes.size
        val rest = m.size - ps - size

        val o = ByteBuffer.allocate(ps + poolSize + rest).order(ByteOrder.LITTLE_ENDIAN)
        o.put(m, 0, ps)
        o.put(m, ps, hs)
        o.putInt(ps + 4, poolSize)
        o.putInt(ps + 20, newSStart)
        o.putInt(ps + 24, newYStart)
        o.position(ps + hs)
        for (x in offs) o.putInt(x)
        o.put(m, ps + hs + sc * 4, yc * 4)
        o.put(data.toByteArray())
        o.put(styleBytes)
        o.put(m, ps + size, rest)
        o.putInt(4, o.capacity())
        return o.array()
    }

    private fun len8(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7F) { o.write((n shr 8) or 0x80); o.write(n and 0xFF) } else o.write(n)
    }

    private fun le16(o: ByteArrayOutputStream, n: Int) {
        o.write(n and 0xFF); o.write((n shr 8) and 0xFF)
    }
}
