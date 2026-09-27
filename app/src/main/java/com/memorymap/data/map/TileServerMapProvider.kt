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
 *
 * Three of the settings exist only because hosts differ: the credit a host
 * requires ([attribution]), the deepest zoom it serves ([maxZoom]), and a key it
 * wants in the URL ([apiKey], substituted where the template says `{key}`).
 */
class TileServerMapProvider(
    override val id: String,
    private val tileTemplate: String,
    override val attribution: String,
    override val maxZoom: Int = DEFAULT_MAX_ZOOM,
    override val minZoom: Int = 0,
    private val apiKey: String = "",
) : MapProvider {

    override fun tileUrl(zoom: Int, x: Int, y: Int): String {
        val z = zoom.coerceIn(minZoom, maxZoom)
        val count = 1 shl z
        // Wrap columns so panning east or west across the antimeridian keeps
        // asking for real tiles; clamp rows because there is nothing past a pole.
        val wrappedX = ((x % count) + count) % count
        val clampedY = y.coerceIn(0, count - 1)
        val address = tileTemplate
            .replace("{z}", z.toString())
            .replace("{x}", wrappedX.toString())
            .replace("{y}", clampedY.toString())
        // A provider that wants a key carries it in the template as `{key}`, so the build
        // setting stays a plain URL template and the key remains a separate value that
        // never has to be edited into a string by hand. A template with no `{key}` ignores
        // the key entirely.
        //
        // With no key configured the placeholder is left standing rather than replaced by
        // nothing: `?key={key}` in a URL says out loud that this build has no key, while
        // `?key=` reads like the provider refused a request that looked complete. The
        // release path refuses this case before it builds (ci/check-tile-provider.py), so
        // what the placeholder costs is a diagnosis, not a release.
        if (apiKey.isBlank()) return address
        return address.replace("{key}", apiKey)
    }

    /** True when this template cannot serve a tile without a key. */
    fun needsApiKey(): Boolean = tileTemplate.contains(KEY_TOKEN)

    /** True when the template can be filled in at all. */
    fun isUsable(): Boolean = isTemplateUsable(tileTemplate)

    companion object {
        const val DEFAULT_MAX_ZOOM = 19

        const val KEY_TOKEN = "{key}"

        /** The placeholder a template must carry to be usable. */
        private val REQUIRED_TOKENS = listOf("{z}", "{x}", "{y}")

        /**
         * The host whose usage policy forbids a distributed app without prior
         * permission (docs/SERVICE_LIMITS.md section 2). `ci/check-tile-provider.py`
         * reads this name rather than duplicating the string, so the two cannot drift
         * apart, and a release that still uses it is refused before it builds.
         */
        const val OSM_HOST = "tile.openstreetmap.org"

        /**
         * The OpenStreetMap raster tiles. The build setting can override it; this
         * is the default when nothing is configured, and it is the right default for a
         * development build rather than for a published app.
         */
        const val OSM_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

        /** The credit OpenStreetMap requires whenever its tiles are shown. */
        const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

        const val OSM_ID = "openstreetmap"

        /** Whether a template can be filled in: all three of `{z}`, `{x}`, `{y}`. */
        fun isTemplateUsable(template: String): Boolean =
            REQUIRED_TOKENS.all { template.contains(it) }

        /** Whether a template points at the policy-limited public OSM server. */
        fun pointsAtPublicOsm(template: String): Boolean = template.contains(OSM_HOST)
    }
}

/**
 * Builds the provider the app actually uses, from the build settings.
 *
 * An empty or malformed template falls back to OpenStreetMap, because a map that draws
 * nothing is worse for the person holding the phone than a map from the wrong host. The
 * fallback is not silent where it matters, though: `ci/check-tile-provider.py` reads the
 * same build setting, and a release whose effective template is malformed — or is still
 * the public OSM server — is refused before anything is built. The fallback protects the
 * screen; the check protects the release.
 */
object MapProviders {

    fun fromConfig(
        tileTemplate: String,
        attribution: String = "",
        id: String = TileServerMapProvider.OSM_ID,
        maxZoom: Int = TileServerMapProvider.DEFAULT_MAX_ZOOM,
        apiKey: String = "",
    ): MapProvider {
        val usable = TileServerMapProvider.isTemplateUsable(tileTemplate)
        return TileServerMapProvider(
            id = if (usable) id else TileServerMapProvider.OSM_ID,
            tileTemplate = if (usable) tileTemplate else TileServerMapProvider.OSM_TEMPLATE,
            // A blank credit becomes the OSM one, because every provider this project
            // documents serves OSM-derived data and losing the credit would break the
            // licence rather than hide a mistake. A release with a non-OSM host and a blank
            // credit is refused by the same check, so this fallback is for development.
            attribution = attribution.ifBlank { TileServerMapProvider.OSM_ATTRIBUTION },
            maxZoom = maxZoom,
            apiKey = apiKey,
        )
    }
}
