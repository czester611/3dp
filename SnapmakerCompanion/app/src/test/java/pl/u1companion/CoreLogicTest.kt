package pl.u1companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.u1companion.core.model.Dimensions
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.printer.job.GcodePatcher
import pl.u1companion.update.UpdateRepository

class CoreLogicTest {

    @Test fun licenseMapping() {
        assertEquals(LicenseFamily.CC0, LicenseFamily.fromText("Creative Commons — Public Domain"))
        assertEquals(LicenseFamily.CC_BY_NC_SA, LicenseFamily.fromText("CC-BY-NC-SA"))
        assertEquals(LicenseFamily.CC_BY_NC, LicenseFamily.fromText("Creative Commons — Attribution  — Noncommercial"))
        assertEquals(LicenseFamily.CC_BY, LicenseFamily.fromText("CC-BY"))
        assertEquals(LicenseFamily.UNKNOWN, LicenseFamily.fromText(null))
    }

    @Test fun fitsU1WithRotation() {
        assertTrue(Dimensions(260f, 100f, 200f).fitsIn(Dimensions.SNAPMAKER_U1))
        assertFalse(Dimensions(100f, 100f, 280f).fitsIn(Dimensions.SNAPMAKER_U1))
    }

    @Test fun patchTemps() {
        val o = GcodePatcher.TempOverrides(mapOf(0 to 245, 1 to 230), bed = 75)
        assertEquals("M104 S245 ; set", GcodePatcher.patchLine("M104 S220 ; set", o, 0))
        assertEquals("M109 T1 S230", GcodePatcher.patchLine("M109 T1 S220", o, 0))
        assertEquals("M104 S0", GcodePatcher.patchLine("M104 S0", o, 0))
        assertEquals("M190 S75", GcodePatcher.patchLine("M190 S60", o, 0))
        assertEquals(
            "PRINT_START TOOL_TEMP=245 T0_TEMP=245 T1_TEMP=230 BED_TEMP=75 TOOL=0",
            GcodePatcher.patchLine("PRINT_START TOOL_TEMP=220 T0_TEMP=220 T1_TEMP=220 BED_TEMP=60 TOOL=0", o, 0),
        )
    }

    @Test fun updateTagParsing() {
        val m = UpdateRepository.TAG_REGEX.matchEntire("v0.3.1+7")!!
        assertEquals("0.3.1", m.groupValues[1]); assertEquals("7", m.groupValues[2])
        assertNull(UpdateRepository.TAG_REGEX.matchEntire("v0.3.1"))
    }
}
