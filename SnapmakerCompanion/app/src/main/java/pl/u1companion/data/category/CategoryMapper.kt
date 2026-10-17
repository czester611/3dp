package pl.u1companion.data.category

import pl.u1companion.core.model.CanonicalCategory
import pl.u1companion.core.model.Category

/**
 * Mapuje natywne kategorie serwisu na [CanonicalCategory].
 *
 * Reguły:
 *  1. Kategoria główna (bez rodzica) -> pierwsze dopasowanie słów kluczowych.
 *  2. Podkategoria dziedziczy kategorię rodzica, chyba że jej nazwa pasuje do kategorii
 *     z [CanonicalCategory.overridesParent] i jednocześnie NIE pasuje do kategorii rodzica
 *     (np. "Hobby & Makers > Tools" -> TOOLS, ale "Healthcare > Medical Tools" zostaje HEALTH).
 *  3. Brak dopasowania -> canonical = null (kategoria dostępna tylko w drzewie danego serwisu).
 */
object CategoryMapper {

    fun annotate(categories: List<Category>): List<Category> {
        val byId = categories.associateBy { it.id }
        val cache = HashMap<String, CanonicalCategory?>()

        fun resolve(c: Category, depth: Int = 0): CanonicalCategory? = cache.getOrPut(c.id) {
            val parent = c.parentId?.let(byId::get)
            if (parent == null || depth > 8) return@getOrPut CanonicalCategory.match(c.name)
            val parentCanon = resolve(parent, depth + 1)
            val own = CanonicalCategory.match(c.name)
            when {
                parentCanon == null -> own
                own != null && own.overridesParent && !parentCanon.matchesName(c.name) -> own
                else -> parentCanon
            }
        }
        return categories.map { it.copy(canonical = resolve(it)) }
    }

    /**
     * Natywne kategorie, które realizują kategorię kanoniczną w danym serwisie - możliwie najwyżej w drzewie
     * (jeśli cała kategoria główna mapuje się na [canonical], nie wypisujemy osobno jej dzieci).
     */
    fun nativeFor(canonical: CanonicalCategory, annotated: List<Category>): List<Category> {
        val byId = annotated.associateBy { it.id }
        return annotated.filter { c ->
            c.canonical == canonical && (c.parentId == null || byId[c.parentId]?.canonical != canonical)
        }
    }

    /** Kanoniczna kategoria dla nazwy kategorii z wyniku wyszukiwania (filtr po stronie klienta). */
    fun canonicalForName(name: String?, annotated: List<Category>): CanonicalCategory? {
        if (name.isNullOrBlank()) return null
        return annotated.firstOrNull { it.name.equals(name, ignoreCase = true) }?.canonical
            ?: CanonicalCategory.match(name)
    }

    /** Drzewo do UI: kategorie główne z dziećmi. */
    fun tree(annotated: List<Category>): List<Pair<Category, List<Category>>> {
        val children = annotated.filter { it.parentId != null }.groupBy { it.parentId }
        return annotated.filter { it.parentId == null }.map { it to children[it.id].orEmpty() }
    }
}
