package pl.u1companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.u1companion.core.model.FileKind
import pl.u1companion.geometry.FitStatus
import pl.u1companion.geometry.GeometryAnalyzer
import pl.u1companion.geometry.MiniXml
import pl.u1companion.geometry.StlParser
import pl.u1companion.geometry.ThreeMfInspector
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GeometryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun binaryStl(triangles: List<FloatArray>, header: String = "binary", trailing: Int = 0): File {
        val buf = ByteBuffer.allocate(84 + 50 * triangles.size + trailing).order(ByteOrder.LITTLE_ENDIAN)
        val h = header.toByteArray().copyOf(80)
        buf.put(h)
        buf.putInt(triangles.size)
        triangles.forEach { t ->
            repeat(3) { buf.putFloat(0f) } // normalna
            t.forEach(buf::putFloat)       // 9 floatów
            buf.putShort(0)
        }
        return tmp.newFile().apply { writeBytes(buf.array()) }
    }

    private val tri1 = floatArrayOf(0f, 0f, 0f, 100f, 0f, 0f, 0f, 50f, 0f)
    private val tri2 = floatArrayOf(-10f, 0f, 0f, 0f, 0f, 30f, 0f, 20f, 5f)

    @Test fun binaryStlBoundingBox() {
        val d = StlParser.boundingBox(binaryStl(listOf(tri1, tri2))).toDimensions()!!
        assertEquals(110f, d.xMm, 1e-3f)
        assertEquals(50f, d.yMm, 1e-3f)
        assertEquals(30f, d.zMm, 1e-3f)
    }

    @Test fun binaryStlWithSolidHeaderIsStillBinary() {
        // Niektóre eksportery CAD zaczynają nagłówek binarnego STL od "solid" - rozmiar pliku rozstrzyga.
        val d = StlParser.boundingBox(binaryStl(listOf(tri1), header = "solid exported by CAD")).toDimensions()!!
        assertEquals(100f, d.xMm, 1e-3f)
    }

    @Test fun binaryStlWithTrailingBytes() {
        val d = StlParser.boundingBox(binaryStl(listOf(tri1), trailing = 7)).toDimensions()!!
        assertEquals(50f, d.yMm, 1e-3f)
    }

    @Test fun asciiStlBoundingBox() {
        val f = tmp.newFile("a.stl").apply {
            writeText(
                """
                solid cube
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 280.5 0 0
                      vertex 0 12.25 -3e1
                    endloop
                  endfacet
                endsolid cube
                """.trimIndent(),
            )
        }
        val d = StlParser.boundingBox(f).toDimensions()!!
        assertEquals(280.5f, d.xMm, 1e-3f)
        assertEquals(12.25f, d.yMm, 1e-3f)
        assertEquals(30f, d.zMm, 1e-3f)
        val g = GeometryAnalyzer.analyze(f, FileKind.STL)
        assertEquals(false, g.fitsU1) // 280.5 > 270
    }

    @Test fun miniXmlHandlesCommentsAndQuotedGt() {
        val tags = mutableListOf<String>()
        MiniXml.scan(StringReader("""<?xml version="1.0"?><!-- a > b --><a x="1>2" y='q'><b/></a>""")) { k, n, a ->
            tags += "$k:$n:${a["x"] ?: ""}"
        }
        assertEquals(listOf("START:a:1>2", "EMPTY:b:", "END:a:"), tags)
    }

    private fun zip(entries: Map<String, String>): File {
        val f = tmp.newFile("m.3mf")
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (name, content) -> z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private val rels = """<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>"""

    /** Struktura jak w 3MF z OrcaSlicer/Bambu: obiekt-kontener w root, siatka w 3D/Objects (rozszerzenie Production). */
    @Test fun threeMfWithProductionComponentsAndSettings() {
        val root = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
             <resources>
              <object id="2" p:UUID="x" type="model">
               <components>
                <component p:path="/3D/Objects/object_1.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0"/>
               </components>
              </object>
             </resources>
             <build>
              <item objectid="2" transform="1 0 0 0 1 0 0 0 1 135 135 10" printable="1"/>
             </build>
            </model>"""
        val obj = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
             <resources>
              <object id="1" type="model">
               <mesh>
                <vertices>
                 <vertex x="-20" y="-10" z="-10"/>
                 <vertex x="20" y="10" z="10"/>
                 <vertex x="0" y="0" z="0"/>
                </vertices>
                <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
               </mesh>
              </object>
             </resources>
            </model>"""
        val settings = """{ "printer_model": "Snapmaker U1", "printer_settings_id": "Snapmaker U1 (0.4 nozzle)", "filament_type": ["PETG", "PLA"] }"""
        val f = zip(
            mapOf(
                "_rels/.rels" to rels,
                "3D/3dmodel.model" to root,
                "3D/Objects/object_1.model" to obj,
                "Metadata/project_settings.config" to settings,
                "Metadata/plate_1.gcode" to "; generated by OrcaSlicer\nG28\n",
            ),
        )
        val info = ThreeMfInspector.inspect(f)
        val d = info.largestItem!!
        assertEquals(40f, d.xMm, 1e-3f)
        assertEquals(20f, d.yMm, 1e-3f)
        assertEquals(20f, d.zMm, 1e-3f)
        assertTrue(info.looksLikeU1)
        assertEquals(listOf("PETG", "PLA"), info.filamentTypes)
        assertEquals(listOf("Metadata/plate_1.gcode"), info.embeddedGcodes)

        val g = GeometryAnalyzer.analyze(f, FileKind.THREE_MF)
        assertEquals(true, g.fitsU1)
        assertEquals(true, g.u1Profile)

        val out = ThreeMfInspector.extractGcode(f, "Metadata/plate_1.gcode", tmp.newFile("p.gcode"))
        assertTrue(out.readText().contains("G28"))
    }

    @Test fun threeMfForOtherPrinterAndTooBig() {
        val root = """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>
            <object id="1" type="model"><mesh><vertices>
              <vertex x="0" y="0" z="0"/><vertex x="300" y="10" z="10"/>
            </vertices></mesh></object></resources>
            <build><item objectid="1"/></build></model>"""
        val f = zip(
            mapOf(
                "3D/3dmodel.model" to root, // bez _rels -> domyślna ścieżka
                "Metadata/project_settings.config" to """{"printer_model":"Bambu Lab X1 Carbon","printer_settings_id":"Bambu Lab X1 Carbon 0.4 nozzle"}""",
            ),
        )
        val g = GeometryAnalyzer.analyze(f, FileKind.THREE_MF)
        assertEquals(false, g.fitsU1)
        assertEquals(false, g.u1Profile)
        assertEquals("Bambu Lab X1 Carbon", g.printerModel)
        assertTrue(g.embeddedGcodes.isEmpty())
    }

    @Test fun rotatedItemIsConservative() {
        // Obrót o 90° wokół Z zamienia X i Y.
        val box = pl.u1companion.geometry.BoundingBox().apply { add(0.0, 0.0, 0.0); add(200.0, 20.0, 5.0) }
        val rot = ThreeMfInspector.parseTransform("0 1 0 -1 0 0 0 0 1 0 0 0")
        val d = ThreeMfInspector.transform(box, rot).toDimensions()!!
        assertEquals(20f, d.xMm, 1e-3f)
        assertEquals(200f, d.yMm, 1e-3f)
        assertNull(ThreeMfInspector.parseTransform("1 2 3"))
    }

    @Test fun corruptFileGivesErrorNotCrash() {
        val f = tmp.newFile("bad.3mf").apply { writeText("not a zip") }
        val g = GeometryAnalyzer.analyze(f, FileKind.THREE_MF)
        assertNull(g.fitsU1)
        assertTrue(g.error != null)
    }

    @Test fun aggregateFitAndU1() {
        assertEquals(FitStatus.UNKNOWN, GeometryAnalyzer.aggregateFit(listOf(null, null)))
        assertEquals(FitStatus.FITS, GeometryAnalyzer.aggregateFit(listOf(true, null)))
        assertEquals(FitStatus.TOO_BIG, GeometryAnalyzer.aggregateFit(listOf(true, false)))
        assertEquals(true, GeometryAnalyzer.aggregateU1(listOf(false, true)))
        assertEquals(false, GeometryAnalyzer.aggregateU1(listOf(false, null)))
        assertFalse(GeometryAnalyzer.aggregateU1(listOf(null)) == true)
    }
}
