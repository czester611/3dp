package pl.u1companion.geometry

import pl.u1companion.core.model.Dimensions

/** Osiowo wyrównany prostopadłościan otaczający (mm). */
class BoundingBox {
    var minX = Double.POSITIVE_INFINITY; private set
    var minY = Double.POSITIVE_INFINITY; private set
    var minZ = Double.POSITIVE_INFINITY; private set
    var maxX = Double.NEGATIVE_INFINITY; private set
    var maxY = Double.NEGATIVE_INFINITY; private set
    var maxZ = Double.NEGATIVE_INFINITY; private set

    val isEmpty: Boolean get() = minX > maxX

    fun add(x: Double, y: Double, z: Double) {
        if (x.isNaN() || y.isNaN() || z.isNaN()) return
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }

    fun add(other: BoundingBox) {
        if (other.isEmpty) return
        add(other.minX, other.minY, other.minZ)
        add(other.maxX, other.maxY, other.maxZ)
    }

    /** Narożniki - do transformacji (wynik po obrocie jest zachowawczy, tj. może być większy niż rzeczywisty). */
    fun corners(): List<DoubleArray> = buildList {
        for (x in doubleArrayOf(minX, maxX)) for (y in doubleArrayOf(minY, maxY)) for (z in doubleArrayOf(minZ, maxZ)) add(doubleArrayOf(x, y, z))
    }

    fun toDimensions(): Dimensions? =
        if (isEmpty) null else Dimensions((maxX - minX).toFloat(), (maxY - minY).toFloat(), (maxZ - minZ).toFloat())
}
