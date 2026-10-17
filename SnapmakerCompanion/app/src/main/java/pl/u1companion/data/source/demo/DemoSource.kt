package pl.u1companion.data.source.demo

import kotlinx.coroutines.delay
import pl.u1companion.core.model.Author
import pl.u1companion.core.model.Dimensions
import pl.u1companion.core.model.FileKind
import pl.u1companion.core.model.License
import pl.u1companion.core.model.LicenseFamily
import pl.u1companion.core.model.ModelDetail
import pl.u1companion.core.model.ModelFile
import pl.u1companion.core.model.ModelSummary
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SortOrder
import pl.u1companion.core.model.SourceId
import pl.u1companion.data.source.AccessMode
import pl.u1companion.data.source.DownloadTicket
import pl.u1companion.data.source.ModelSource
import pl.u1companion.data.source.PageRequest
import pl.u1companion.data.source.SearchPage
import pl.u1companion.data.source.SourceCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/** Fałszywe dane do pracy nad UI offline i do testów (włączane filtrem źródła "Demo"). */
@Singleton
class DemoSource @Inject constructor() : ModelSource {
    override val id = SourceId.DEMO
    override val capabilities = SourceCapabilities(
        access = AccessMode.DEMO,
        supportedSorts = SortOrder.entries.toSet(),
        serverCategoryFilter = true,
        serverLicenseFilter = true,
        printReadyInSearch = true,
        inAppDownload = false,
        hasPaidContent = true,
    )

    private val names = listOf(
        "Organizer na kable", "Uchwyt na telefon", "Benchy 4-kolorowy", "Doniczka samonawadniająca",
        "Klips do torebek", "Zawias print-in-place", "Smok artykułowany", "Pudełko z gwintem",
        "Uchwyt ścienny na narzędzia", "Wieszak na słuchawki", "Śruba M8 test tolerancji", "Lampka księżyc",
    )
    private val licenses = LicenseFamily.entries.filter { it != LicenseFamily.UNKNOWN }

    private fun item(i: Int): ModelSummary {
        val lic = licenses[i % licenses.size]
        return ModelSummary(
            source = SourceId.DEMO,
            id = i.toString(),
            title = names[i % names.size] + " #$i",
            author = Author("maker${i % 17}"),
            thumbnailUrl = "https://picsum.photos/seed/u1demo$i/480/360",
            webUrl = "https://example.invalid/model/$i",
            likes = (i * 37) % 5000,
            downloads = (i * 113) % 40000,
            publishedAtEpochMs = 1_760_000_000_000L - i * 3_600_000L,
            license = License(lic.name, lic.label, lic),
            isFree = i % 7 != 0,
            priceCents = if (i % 7 == 0) 499 else null,
            category = listOf("Gadżety", "Dom", "Narzędzia", "Zabawki", "Testy")[i % 5],
            hasPrintReadyFile = i % 3 == 0,
            hasU1Profile = i % 6 == 0,
        )
    }

    override suspend fun search(query: SearchQuery, page: PageRequest): SearchPage {
        delay(300)
        val start = page.cursor?.toIntOrNull() ?: 0
        val all = (start until start + page.size).map(::item)
            .filter { query.text.isBlank() || it.title.contains(query.text, ignoreCase = true) || start > 0 }
        val next = start + page.size
        return SearchPage(all, if (next >= 300) null else next.toString(), 300)
    }

    override suspend fun details(modelId: String): ModelDetail {
        val i = modelId.toInt()
        val s = item(i)
        return ModelDetail(
            summary = s,
            descriptionHtml = "<p>Model demonstracyjny. Opis, instrukcje druku, zalecany filament.</p>",
            images = (0..3).map { "https://picsum.photos/seed/u1demo${i}_$it/960/720" },
            files = buildList {
                add(ModelFile("$i-stl", "model_$i.stl", 2_400_000, FileKind.STL))
                add(ModelFile("$i-3mf", "model_$i.3mf", 1_100_000, FileKind.THREE_MF))
                if (s.hasPrintReadyFile == true) add(ModelFile("$i-gc", "model_${i}_PLA_0.2mm.gcode", 5_300_000, FileKind.GCODE))
                if (s.hasU1Profile == true) add(ModelFile("$i-u1", "model_${i}_Snapmaker_U1_PETG.gcode", 5_900_000, FileKind.GCODE, printerHint = "Snapmaker U1"))
            },
            tags = listOf("demo", "pla", "functional"),
            dimensions = Dimensions(40f + i % 300, 60f + i % 200, 20f + i % 280),
        )
    }

    override suspend fun resolveDownload(modelId: String, file: ModelFile) =
        DownloadTicket("https://example.invalid/${file.name}", file.name)

    override fun webSearchUrl(text: String) = "https://example.invalid/search?q=$text"
}
