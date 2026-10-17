package pl.u1companion.data.source

import pl.u1companion.core.model.SourceId
import pl.u1companion.data.source.demo.DemoSource
import pl.u1companion.data.source.printables.PrintablesSource
import pl.u1companion.data.source.thingiverse.ThingiverseSource
import pl.u1companion.data.source.web.WebAssistedSource
import javax.inject.Inject
import javax.inject.Singleton

/** Rejestr wszystkich adapterów. Nowy serwis = nowa klasa ModelSource + wpis tutaj. */
@Singleton
class SourceRegistry @Inject constructor(
    printables: PrintablesSource,
    thingiverse: ThingiverseSource,
    demo: DemoSource,
) {
    private val all: Map<SourceId, ModelSource> =
        (listOf(printables, thingiverse, demo) + WebAssistedSource.defaults()).associateBy { it.id }

    operator fun get(id: SourceId): ModelSource = all.getValue(id)

    fun allSources(): List<ModelSource> = SourceId.entries.mapNotNull { all[it] }

    /** Źródła, które potrafią zwracać wyniki do zagregowanej listy. */
    fun searchable(): List<ModelSource> = allSources().filter {
        it.capabilities.access in setOf(AccessMode.OFFICIAL_API, AccessMode.UNOFFICIAL_API, AccessMode.DEMO)
    }

    fun webAssisted(): List<ModelSource> = allSources().filter {
        it.capabilities.access == AccessMode.ASSISTED_WEBVIEW || it.capabilities.access == AccessMode.LINK_OUT
    }
}
