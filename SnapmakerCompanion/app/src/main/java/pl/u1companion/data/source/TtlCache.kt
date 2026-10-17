package pl.u1companion.data.source

/**
 * Prosty cache w pamięci z czasem życia wpisów (TTL) i limitem rozmiaru (najstarsze usuwane).
 * Używany m.in. przez Cults3D (limit ~500 zapytań/dzień) - wyniki wyszukiwania na 1 h.
 * [clock] wstrzykiwany, żeby testy mogły "przesuwać czas".
 */
class TtlCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val maxEntries: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<V>(val value: V, val storedAt: Long)

    private val map = LinkedHashMap<K, Entry<V>>()

    @Synchronized
    fun get(key: K): V? {
        val e = map[key] ?: return null
        if (clock() - e.storedAt >= ttlMs) {
            map.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map.remove(key)
        map[key] = Entry(value, clock())
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size

    /** Zwraca wartość z cache albo liczy ją [load] i zapisuje (błędy nie są cache'owane). */
    suspend fun getOrLoad(key: K, load: suspend () -> V): V = get(key) ?: load().also { put(key, it) }
}
