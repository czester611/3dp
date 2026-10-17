package pl.u1companion.printer.job

import java.io.File

/**
 * Minimalne zmiany w GOTOWYM G-code (bez ponownego slicingu):
 *  - temperatury dysz (M104/M109 S.. T..), stołu (M140/M190 S..),
 *  - parametry makra PRINT_START z profilu Orca U1 (TOOL_TEMP=, T0_TEMP=..T3_TEMP=, BED_TEMP=).
 *
 * Czego NIE da się zmienić bez slicingu: wysokość warstwy, wypełnienie, podpory, prędkości,
 * chłodzenie, retrakcja. Dlatego zmiana PLA->PETG na gotowym G-code to tylko "korekta temperatur"
 * i UI pokazuje ostrzeżenie; właściwa ścieżka to ponowny slicing na serwerze.
 *
 * Mapowanie głowic NIE jest robione przez przepisywanie T<n> - używamy SET_PRINT_EXTRUDER_MAP
 * (firmware U1), co jest mniej inwazyjne.
 */
object GcodePatcher {

    data class TempOverrides(
        /** logiczne narzędzie -> temperatura dyszy */
        val nozzle: Map<Int, Int>,
        val bed: Int?,
    )

    private val tempCmd = Regex("^(M104|M109)\\b(.*)$", RegexOption.IGNORE_CASE)
    private val bedCmd = Regex("^(M140|M190)\\b(.*)$", RegexOption.IGNORE_CASE)
    private val sParam = Regex("\\bS(\\d+(?:\\.\\d+)?)")
    private val tParam = Regex("\\bT(\\d+)")
    private val toolSelect = Regex("^T(\\d+)\\b")

    fun patch(input: File, output: File, o: TempOverrides) {
        var currentTool = 0
        output.bufferedWriter().use { w ->
            input.bufferedReader().useLines { lines ->
                lines.forEach { line -> w.write(patchLine(line, o, currentTool).also { currentTool = trackTool(line, currentTool) }); w.newLine() }
            }
        }
    }

    internal fun patchLine(line: String, o: TempOverrides, currentTool: Int): String {
        val code = line.substringBefore(';')
        val comment = line.substring(code.length)
        tempCmd.find(code.trim())?.let {
            val t = tParam.find(code)?.groupValues?.get(1)?.toInt() ?: currentTool
            val target = o.nozzle[t] ?: return line
            if (!sParam.containsMatchIn(code)) return line
            if (sParam.find(code)?.groupValues?.get(1)?.toDouble() == 0.0) return line // wyłączanie grzania zostawiamy
            return sParam.replace(code, "S$target") + comment
        }
        bedCmd.find(code.trim())?.let {
            val target = o.bed ?: return line
            if (sParam.find(code)?.groupValues?.get(1)?.toDouble() == 0.0) return line
            return sParam.replace(code, "S$target") + comment
        }
        if (code.trimStart().startsWith("PRINT_START", ignoreCase = true)) {
            var c = code
            o.nozzle.forEach { (t, temp) -> c = c.replace(Regex("\\bT${t}_TEMP=\\d+"), "T${t}_TEMP=$temp") }
            o.bed?.let { b -> c = c.replace(Regex("\\bBED_TEMP=\\d+"), "BED_TEMP=$b") }
            val initial = Regex("\\bTOOL=(\\d+)").find(c)?.groupValues?.get(1)?.toInt()
            initial?.let { o.nozzle[it] }?.let { temp -> c = c.replace(Regex("\\bTOOL_TEMP=\\d+"), "TOOL_TEMP=$temp") }
            return c + comment
        }
        return line
    }

    private fun trackTool(line: String, current: Int): Int =
        toolSelect.find(line.trim())?.groupValues?.get(1)?.toInt() ?: current
}
