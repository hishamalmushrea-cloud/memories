package com.memorymap.domain.map

/**
 * The seam between the app and whatever serves the map tiles.
 *
 * The spec is explicit: no Google Maps API, and no permanent tie to a map
 * library that could be abandoned. So the app depends only on this interface.
 * Swapping the tile source, or replacing the renderer with an SDK later, is a
 * matter of providing a different implementation; no screen changes.
 *
 * Implementations must be usable offline-tolerant: a tile that never loads is a
 * blank square, never a crash, and the cached tiles keep working.
 */
interface MapProvider {

    /** Stable identifier, used for logging and for remembering the choice. */
    val id: String

    /**
     * The credit the tile server requires. It is always shown on the map:
     * using open tiles without attribution breaks their terms.
     */
    val attribution: String

    val minZoom: Int

    val maxZoom: Int

    /**
     * URL of one raster tile. [x] may be outside `0 until 2^zoom`; the provider
     * wraps it, because the map pans across the antimeridian.
     */
    fun tileUrl(zoom: Int, x: Int, y: Int): String
}
