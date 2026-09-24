package com.memorymap.data.map

import com.memorymap.domain.map.MapProvider

/**
 * A plain XYZ raster tile server, which is what OpenStreetMap and most open tile
 * hosts serve.
 *
 * The template comes from `BuildConfig.MAP_TILE_SERVER`, so pointing the app at
 * a different host — your own, or a paid provider with real capacity — is a
 * build setting and not a code change. Public tile servers are not unlimited,
 * which is exactly why this is configurable and why the tiles are cached.
 */
class TileServerMapProvider(
    override val id: String,
    private val tileTemplate: String,
    override val attribution: String,
    override val maxZoom: Int = DEFAULT_MAX_ZOOM,
    override val minZoom: Int = 0,
) : MapProvider {

    override fun tileUrl(zoom: Int, x: Int, y: Int): String {
        val z = zoom.coerceIn(minZoom, maxZoom)
        val count = 1 shl z
        // Wrap columns so panning east or west across the antimeridian keeps
        // asking for real tiles; clamp rows because there is nothing past a pole.
        val wrappedX = ((x % count) + count) % count
        val clampedY = y.coerceIn(0, count - 1)
        return tileTemplate
            .replace("{z}", z.toString())
            .replace("{x}", wrappedX.toString())
            .replace("{y}", clampedY.toString())
    }

    companion object {
        const val DEFAULT_MAX_ZOOM = 19

        /**
         * The OpenStreetMap raster tiles. The build setting can override it; this
         * is the default when nothing is configured.
         */
        const val OSM_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

        /** The credit OpenStreetMap requires whenever its tiles are shown. */
        const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

        const val OSM_ID = "openstreetmap"
    }
}

/**
 * Builds the provider the app actually uses, from the build settings.
 *
 * An empty or malformed template falls back to OpenStreetMap rather than
 * producing a map that silently shows nothing.
 */
object MapProviders {

    fun fromConfig(tileTemplate: String, attribution: String): MapProvider {
        val template = tileTemplate.takeIf { it.contains("{z}") && it.contains("{x}") && it.contains("{y}") }
            ?: TileServerMapProvider.OSM_TEMPLATE
        val credit = attribution.ifBlank { TileServerMapProvider.OSM_ATTRIBUTION }
        return TileServerMapProvider(
            id = TileServerMapProvider.OSM_ID,
            tileTemplate = template,
            attribution = credit,
        )
    }
}
