package pl.u1companion.geometry

import java.io.Reader

/**
 * Minimalny strumieniowy skaner XML (tylko znaczniki i atrybuty, bez tekstu).
 * Wystarcza do plików 3MF (pliki .model w katalogu 3D) i działa w testach JVM (bez XmlPullParser z Androida).
 * Pomija komentarze, instrukcje przetwarzania, DOCTYPE i CDATA.
 */
internal object MiniXml {

    enum class Kind { START, END, EMPTY }

    fun interface Handler {
        fun onTag(kind: Kind, name: String, attrs: Map<String, String>)
    }

    fun scan(reader: Reader, handler: Handler) {
        val r = reader.buffered(1 shl 16)
        val sb = StringBuilder(256)
        while (true) {
            var c = r.read()
            while (c != -1 && c != '<'.code) c = r.read()
            if (c == -1) return
            sb.setLength(0)
            // czytamy do '>' z uwzględnieniem cudzysłowów
            var quote = 0
            while (true) {
                c = r.read()
                if (c == -1) return
                if (quote != 0) {
                    if (c == quote) quote = 0
                } else if (c == '"'.code || c == '\''.code) {
                    quote = c
                } else if (c == '>'.code) break
                sb.append(c.toChar())
                // komentarz / CDATA mogą zawierać '>' - obsługujemy je osobno
                if (sb.length == 3 && sb.startsWith("!--")) { skipUntil(r, "-->"); sb.setLength(0); break }
                if (sb.length == 8 && sb.startsWith("![CDATA[")) { skipUntil(r, "]]>"); sb.setLength(0); break }
            }
            if (sb.isEmpty()) continue
            val first = sb[0]
            if (first == '?' || first == '!') continue
            if (first == '/') {
                handler.onTag(Kind.END, sb.substring(1).trim(), emptyMap())
                continue
            }
            val selfClosing = sb.endsWith("/")
            if (selfClosing) sb.setLength(sb.length - 1)
            val (name, attrs) = parseTag(sb)
            handler.onTag(if (selfClosing) Kind.EMPTY else Kind.START, name, attrs)
        }
    }

    private fun skipUntil(r: Reader, end: String) {
        val window = StringBuilder(end.length + 1)
        while (true) {
            val c = r.read()
            if (c == -1) return
            window.append(c.toChar())
            if (window.length > end.length) window.deleteCharAt(0)
            if (window.length == end.length && window.contentEquals(end)) return
        }
    }

    internal fun parseTag(s: CharSequence): Pair<String, Map<String, String>> {
        var i = 0
        val n = s.length
        while (i < n && !s[i].isWhitespace()) i++
        val name = s.substring(0, i)
        val attrs = HashMap<String, String>(8)
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            val ks = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            val key = s.substring(ks, i)
            while (i < n && s[i] != '=') i++
            i++ // '='
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) break
            val q = s[i]
            if (q != '"' && q != '\'') break
            val vs = ++i
            while (i < n && s[i] != q) i++
            if (key.isNotEmpty()) attrs[key] = unescape(s.substring(vs, minOf(i, n)))
            i++
        }
        return name to attrs
    }

    private fun unescape(v: String): String =
        if ('&' !in v) v else v.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
