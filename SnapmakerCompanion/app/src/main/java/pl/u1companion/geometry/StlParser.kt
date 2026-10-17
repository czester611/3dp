package pl.u1companion.geometry

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wymiary siatki STL (binarny i ASCII) liczone strumieniowo - bez trzymania trójkątów w pamięci.
 *
 * Rozpoznanie formatu: binarny ma 80 B nagłówka + uint32 liczby trójkątów + 50 B na trójkąt.
 * Jeśli rozmiar pliku == 84 + 50*n -> binarny (nawet gdy nagłówek zaczyna się od "solid",
 * co robi część eksporterów CAD). W przeciwnym razie, gdy zaczyna się od "solid" -> ASCII.
 */
object StlParser {

    fun boundingBox(file: File): BoundingBox =
        file.inputStream().buffered(1 shl 16).use { boundingBox(it, file.length()) }

    fun boundingBox(input: InputStream, length: Long?): BoundingBox {
        val inp = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        inp.mark(84)
        val header = ByteArray(84)
        val read = inp.readNBytesCompat(header)
        inp.reset()
        if (read < 84) return parseAscii(inp) // za krótki na binarny - spróbuj ASCII
        val count = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val looksAscii = String(header, 0, 5, Charsets.US_ASCII).equals("solid", ignoreCase = true)
        // Część eksporterów dopisuje bajty na końcu binarnego STL - dopuszczamy nadmiar, gdy nagłówek nie wygląda na ASCII.
        val binarySizeMatches = length != null &&
            (length == 84 + 50 * count || (!looksAscii && count > 0 && length > 84 + 50 * count))
        return when {
            binarySizeMatches -> parseBinary(inp, count)
            looksAscii -> parseAscii(inp)
            length == null -> parseBinary(inp, count)
            else -> throw IOException("Nierozpoznany format STL (rozmiar $length, trójkątów $count)")
        }
    }

    private fun parseBinary(inp: InputStream, count: Long): BoundingBox {
        val box = BoundingBox()
        if (inp.readNBytesCompat(ByteArray(84)) < 84) throw IOException("Uszkodzony STL")
        val tri = ByteArray(50)
        val bb = ByteBuffer.wrap(tri).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            if (inp.readNBytesCompat(tri) < 50) throw IOException("STL ucięty po $i trójkątach")
            // 12 B normalna, potem 3 wierzchołki po 3 float, 2 B atrybutów
            for (v in 0 until 3) {
                val o = 12 + v * 12
                box.add(bb.getFloat(o).toDouble(), bb.getFloat(o + 4).toDouble(), bb.getFloat(o + 8).toDouble())
            }
        }
        return box
    }

    private fun parseAscii(inp: InputStream): BoundingBox {
        val box = BoundingBox()
        inp.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("vertex", ignoreCase = true)) {
                    val p = line.split(WS)
                    if (p.size >= 4) box.add(p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
                }
            }
        }
        if (box.isEmpty) throw IOException("Brak wierzchołków w STL ASCII")
        return box
    }

    private val WS = Regex("\\s+")
}

/** InputStream.readNBytes jest od API 33 - własna wersja. */
internal fun InputStream.readNBytesCompat(buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) break
        off += n
    }
    return off
}
