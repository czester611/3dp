package pl.u1companion

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.u1companion.core.model.CanonicalCategory
import pl.u1companion.core.model.Category
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchFilters
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceCategoryRef
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.category.CategoryMapper
import pl.u1companion.data.category.CategoryRepository
import pl.u1companion.data.source.AccessMode
import pl.u1companion.data.source.ClientSideFilter
import pl.u1companion.data.source.DownloadTicket
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SearchPage
import pl.u1companion.data.source.SourceCapabilities
import pl.u1companion.data.source.printables.PrintablesSource
import pl.u1companion.geometry.FitStatus
import pl.u1companion.geometry.ModelFacts

class BrowseLogicTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val printables = PrintablesSource(OkHttpClient(), json)

    /** Fixture: odpowiedź Printables `categories { id name level path { id } }` zapisana 2026-10-17. */
    private val printablesCats: List<Category> by lazy {
        val raw = javaClass.getResource("/fixtures/printables_categories.json")!!.readText()
        CategoryMapper.annotate(printables.parseCategoriesJson(raw))
    }

    private fun cat(name: String) = printablesCats.first { it.name == name }

    @Test fun printablesCategoriesParsedWithHierarchy() {
        assertEquals(89, printablesCats.size)
        assertEquals(14, printablesCats.count { it.parentId == null })
        assertEquals("3", cat("Kitchen").parentId) // Household
    }

    @Test fun canonicalMappingOfPrintablesTree() {
        assertEquals(CanonicalCategory.HOUSEHOLD, cat("Household").canonical)
        assertEquals(CanonicalCategory.HOUSEHOLD, cat("Kitchen").canonical)
        assertEquals(CanonicalCategory.PRINTER_PARTS, cat("3D Printers").canonical)
        assertEquals(CanonicalCategory.TEST_MODELS, cat("Test Models").canonical)        // nadpisuje rodzica
        assertEquals(CanonicalCategory.TOOLS, cat("Tools").canonical)                    // Hobby & Makers > Tools
        assertEquals(CanonicalCategory.TOOLS, cat("Organizers").canonical)
        assertEquals(CanonicalCategory.HEALTH, cat("Medical Tools").canonical)           // nie przejmuje TOOLS
        assertEquals(CanonicalCategory.GADGETS, cat("Video Games").canonical)            // dziedziczy po Gadgets
        assertEquals(CanonicalCategory.SPORTS, cat("Winter Sports").canonical)
        assertEquals(CanonicalCategory.SEASONAL, cat("Seasonal designs").canonical)
        assertEquals(CanonicalCategory.MINIATURES, cat("Tabletop Miniatures").canonical)
        assertEquals(CanonicalCategory.FASHION, cat("Women").canonical)
        assertTrue("każda kategoria główna Printables ma odpowiednik", printablesCats.filter { it.parentId == null }.all { it.canonical != null })
    }

    @Test fun nativeCategoriesForCanonical() {
        assertEquals(listOf("3"), CategoryMapper.nativeFor(CanonicalCategory.HOUSEHOLD, printablesCats).map { it.id })
        assertEquals(setOf("Tools", "Organizers"), CategoryMapper.nativeFor(CanonicalCategory.TOOLS, printablesCats).map { it.name }.toSet())
        assertEquals(listOf("12"), CategoryMapper.nativeFor(CanonicalCategory.TEST_MODELS, printablesCats).map { it.id })
    }

    @Test fun thingiverseStyleNames() {
        assertEquals(CanonicalCategory.PRINTER_PARTS, CanonicalCategory.match("3D Printing"))
        assertEquals(CanonicalCategory.TOYS_GAMES, CanonicalCategory.match("Toys & Games"))
        assertEquals(CanonicalCategory.ART, CanonicalCategory.match("Art"))
        assertNull(CanonicalCategory.match("Models"))
        assertNull(CanonicalCategory.match("Parts")) // "art" nie może pasować w środku słowa
    }

    // --- planowanie zapytań per źródło ---

    private class FakeSource(override val id: SourceId, serverCategories: Boolean) : ModelSource {
        override val capabilities = SourceCapabilities(AccessMode.DEMO, SortOrder.entries.toSet(), serverCategoryFilter = serverCategories)
        override suspend fun search(query: SearchQuery, page: PageRequest) = SearchPage(emptyList(), null)
        override suspend fun details(modelId: String): ModelDetail = error("n/a")
        override suspend fun resolveDownload(modelId: String, file: ModelFile) = DownloadTicket("", "")
        override fun webSearchUrl(text: String) = ""
    }

    @Test fun planWithoutCategory() {
        val p = CategoryRepository.planFor(FakeSource(SourceId.PRINTABLES, true), SearchFilters(), printablesCats)
        assertEquals(listOf<String?>(null), p.nativeCategoryIds)
        assertTrue(p.notes.isEmpty())
    }

    @Test fun planCanonicalOnServerSideSource() {
        val p = CategoryRepository.planFor(FakeSource(SourceId.PRINTABLES, true), SearchFilters(category = CanonicalCategory.TOOLS), printablesCats)
        assertEquals(2, p.nativeCategoryIds.size)
        assertNull(p.clientCategory)
    }

    @Test fun planExplicitSourceCategoryWins() {
        val f = SearchFilters(category = CanonicalCategory.HOUSEHOLD, sourceCategory = SourceCategoryRef(SourceId.PRINTABLES, "7", "Kitchen"))
        assertEquals(listOf<String?>("7"), CategoryRepository.planFor(FakeSource(SourceId.PRINTABLES, true), f, printablesCats).nativeCategoryIds)
        // inne źródło dostaje kategorię kanoniczną
        val other = CategoryRepository.planFor(FakeSource(SourceId.THINGIVERSE, false), f, emptyList())
        assertEquals(CanonicalCategory.HOUSEHOLD, other.clientCategory)
        assertEquals(1, other.notes.size)
    }

    @Test fun planNoNativeEquivalent() {
        val cats = CategoryMapper.annotate(listOf(Category("a", "Household", source = SourceId.DEMO)))
        val p = CategoryRepository.planFor(FakeSource(SourceId.DEMO, true), SearchFilters(category = CanonicalCategory.SCANS), cats)
        assertEquals(listOf<String?>(null), p.nativeCategoryIds)
        assertEquals(CanonicalCategory.SCANS, p.clientCategory)
        assertTrue(p.notes.single().contains("brak odpowiednika"))
    }

    // --- filtry klienckie ---

    private fun summary(category: String? = null, u1: Boolean? = null) = ModelSummary(
        source = SourceId.PRINTABLES, id = "1", title = "t", author = null, thumbnailUrl = null, webUrl = "",
        category = category, hasU1Profile = u1,
    )

    @Test fun fitsFilterKeepsUnknownDropsTooBig() {
        val f = SearchFilters(onlyFitsU1 = true)
        assertTrue(ClientSideFilter.accept(summary(), f, null))
        assertTrue(ClientSideFilter.accept(summary(), f, ModelFacts(FitStatus.FITS, null, null)))
        assertFalse(ClientSideFilter.accept(summary(), f, ModelFacts(FitStatus.TOO_BIG, null, null)))
    }

    @Test fun u1ProfileFromFacts() {
        val f = SearchFilters(onlyU1Profile = true)
        assertFalse(ClientSideFilter.accept(summary(), f, null))
        assertTrue(ClientSideFilter.accept(summary(), f, ModelFacts(FitStatus.UNKNOWN, true, null)))
        assertTrue(ClientSideFilter.accept(summary(u1 = true), f, null))
    }

    @Test fun clientCategoryFilter() {
        val f = SearchFilters(category = CanonicalCategory.HOUSEHOLD)
        assertTrue(ClientSideFilter.accept(summary("Kitchen"), f, null, CanonicalCategory.HOUSEHOLD, printablesCats))
        assertFalse(ClientSideFilter.accept(summary("Toys & Games"), f, null, CanonicalCategory.HOUSEHOLD, printablesCats))
        assertTrue("nieznana kategoria zostaje", ClientSideFilter.accept(summary(null), f, null, CanonicalCategory.HOUSEHOLD, printablesCats))
    }

    @Test fun u1FileNameHeuristic() {
        fun f(n: String) = ModelFile("1", n, null, pl.u1companion.core.model.FileKind.fromFileName(n)).isLikelyForU1
        assertTrue(f("benchy_U1.gcode")); assertTrue(f("Snapmaker U1 PETG.3mf")); assertTrue(f("part-u1-0.2mm.gcode"))
        assertFalse(f("MU1_bracket.stl")); assertFalse(f("U10_case.stl"))
    }

    // --- sortowanie ---

    @Test fun printablesSortResolution() {
        assertTrue(printables.resolveSort(SortOrder.NEWEST, true).exact)
        val dl = printables.resolveSort(SortOrder.MOST_DOWNLOADED, true)
        assertFalse(dl.exact); assertEquals("makes_count", dl.nativeValue)
        assertFalse(printables.resolveSort(SortOrder.RELEVANCE, hasText = false).exact)
        assertEquals("best_match", printables.resolveSort(SortOrder.RELEVANCE, hasText = true).nativeValue)
    }
}
