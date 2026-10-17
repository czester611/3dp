package pl.u1companion.core.model

import kotlinx.serialization.Serializable

/**
 * Wspólna taksonomia kategorii ponad serwisami. Każdy serwis ma własne drzewo kategorii
 * (Printables: 14 głównych + podkategorie, Thingiverse: własna lista) - użytkownik wybiera
 * kategorię kanoniczną, a [pl.u1companion.data.category.CategoryMapper] tłumaczy ją na
 * kategorie natywne każdego serwisu po słowach kluczowych w nazwach.
 *
 * Kolejność wpisów ma znaczenie: przy dopasowaniu wygrywa pierwszy pasujący (bardziej specyficzne wyżej).
 */
@Serializable
enum class CanonicalCategory(val label: String, val keywords: List<String>) {
    TEST_MODELS("Modele testowe / kalibracja", listOf("test model", "calibration", "benchmark", "test")),
    PRINTER_PARTS("Części i akcesoria do drukarek", listOf("3d printer", "printer", "3d printing", "upgrade")),
    TOOLS("Narzędzia i organizery", listOf("tool", "organizer", "organiser", "storage")),
    HOUSEHOLD("Dom i ogród", listOf("household", "home", "house", "kitchen", "bathroom", "bedroom", "living room", "garage", "garden", "office", "pets", "pet")),
    GADGETS("Gadżety i elektronika", listOf("gadget", "audio", "computer", "phone", "photo", "video", "portable", "virtual reality")),
    HOBBY("Hobby i majsterkowanie", listOf("hobby", "maker", "automotive", "electronics", "mechanical", "music", "robot", "rc ")),
    MINIATURES("Miniatury i gry bitewne", listOf("miniature", "tabletop", "terrain")),
    TOYS_GAMES("Zabawki i gry", listOf("toy", "game", "puzzle")),
    SEASONAL("Sezonowe", listOf("seasonal", "halloween", "christmas", "easter", "summer", "winter")),
    ART("Sztuka i design", listOf("art", "design", "sculpture", "decor", "plates & logos")),
    COSTUMES("Cosplay i kostiumy", listOf("costume", "cosplay", "mask", "prop")),
    FASHION("Moda i biżuteria", listOf("fashion", "jewelry", "jewellery", "men", "women")),
    LEARNING("Edukacja", listOf("learning", "education", "math", "physics", "chemistry", "biology", "engineering")),
    HEALTH("Zdrowie", listOf("health", "medical")),
    SPORTS("Sport i outdoor", listOf("sport")),
    SCANS("Skany i modele świata", listOf("scan", "world", "architecture", "animal", "historical")),
    ;

    /**
     * Kategorie, które podkategoria może "przejąć" od rodzica, gdy sama do nich pasuje
     * (np. Printables "3D Printers > Test Models" -> TEST_MODELS, "Hobby & Makers > Tools" -> TOOLS).
     * Pozostałe podkategorie dziedziczą kategorię rodzica (np. "Gadgets > Video Games" zostaje GADGETS).
     */
    val overridesParent: Boolean get() = this == TEST_MODELS || this == TOOLS || this == MINIATURES

    /** Czy nazwa zawiera którekolwiek słowo kluczowe tej kategorii. */
    fun matchesName(name: String): Boolean {
        val n = pad(name)
        return keywords.any { matchesKeyword(n, it) }
    }

    companion object {
        private fun pad(name: String) = " " + name.lowercase().replace('&', ' ').replace('-', ' ') + " "

        fun match(name: String): CanonicalCategory? {
            val n = pad(name)
            return entries.firstOrNull { c -> c.keywords.any { k -> matchesKeyword(n, k) } }
        }

        /** Dopasowanie na granicy słowa z prefiksem (np. "tool" pasuje do "tools", ale nie do "stool"). */
        private fun matchesKeyword(padded: String, keyword: String): Boolean {
            val k = keyword.lowercase().replace('&', ' ').trim()
            return Regex("(?<![a-z])" + Regex.escape(k)).containsMatchIn(padded)
        }
    }
}
