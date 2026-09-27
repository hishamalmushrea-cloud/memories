package com.memorymap.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile source contract.
 *
 * Two things must hold: the URL template is filled in correctly, and a bad build
 * setting degrades to OpenStreetMap instead of producing a map that silently
 * shows nothing.
 */
class TileServerMapProviderTest {

    private val template = "https://tiles.example.org/{z}/{x}/{y}.png"

    @Test
    fun `the template placeholders are filled in`() {
        val provider = provider()

        assertEquals("https://tiles.example.org/5/10/20.png", provider.tileUrl(5, 10, 20))
    }

    @Test
    fun `columns wrap so crossing the antimeridian still asks for real tiles`() {
        val provider = provider()

        // At zoom 2 there are 4 columns, so -1 and 3 are the same column.
        assertEquals(provider.tileUrl(2, 3, 1), provider.tileUrl(2, -1, 1))
        assertEquals(provider.tileUrl(2, 0, 1), provider.tileUrl(2, 4, 1))
        assertEquals(provider.tileUrl(2, 1, 1), provider.tileUrl(2, -7, 1))
    }

    @Test
    fun `rows are clamped because there is nothing past a pole`() {
        val provider = provider()

        assertEquals("https://tiles.example.org/2/1/0.png", provider.tileUrl(2, 1, -5))
        assertEquals("https://tiles.example.org/2/1/3.png", provider.tileUrl(2, 1, 99))
    }

    @Test
    fun `the zoom is clamped to what the provider serves`() {
        val provider = provider(maxZoom = 18)

        assertTrue(provider.tileUrl(30, 1, 1).startsWith("https://tiles.example.org/18/"))
        assertEquals("https://tiles.example.org/0/0/0.png", provider.tileUrl(-4, 0, 0))
    }

    @Test
    fun `the attribution the tile host requires is exposed`() {
        assertEquals("© Example tiles", provider().attribution)
    }

    @Test
    fun `a template without the placeholders falls back to openstreetmap`() {
        val provider = MapProviders.fromConfig(
            tileTemplate = "https://broken.example.org/tiles.png",
            attribution = "© Broken",
        )

        assertEquals("https://tile.openstreetmap.org/0/0/0.png", provider.tileUrl(0, 0, 0))
        assertEquals(TileServerMapProvider.OSM_ID, provider.id)
    }

    @Test
    fun `a valid custom template is used as given`() {
        val provider = MapProviders.fromConfig(
            tileTemplate = "https://my.tiles/{z}/{x}/{y}.jpg",
            attribution = "© My tiles",
        )

        assertEquals("https://my.tiles/3/2/1.jpg", provider.tileUrl(3, 2, 1))
        assertEquals("© My tiles", provider.attribution)
    }

    @Test
    fun `a blank attribution still credits openstreetmap`() {
        val provider = MapProviders.fromConfig(tileTemplate = template, attribution = "")

        assertEquals(TileServerMapProvider.OSM_ATTRIBUTION, provider.attribution)
    }

    @Test
    fun `a keyed template substitutes the key where it says to`() {
        val provider = TileServerMapProvider(
            id = "maptiler",
            tileTemplate = "https://api.example.org/maps/basic/{z}/{x}/{y}.png?key={key}",
            attribution = "© Example",
            apiKey = "abc123",
        )

        assertEquals(
            "https://api.example.org/maps/basic/3/2/1.png?key=abc123",
            provider.tileUrl(3, 2, 1),
        )
        assertTrue(provider.needsApiKey())
        assertTrue(provider.isUsable())
    }

    @Test
    fun `a template without a key placeholder ignores the key entirely`() {
        val provider = TileServerMapProvider(
            id = "plain",
            tileTemplate = template,
            attribution = "© Example tiles",
            apiKey = "unused",
        )

        assertEquals("https://tiles.example.org/1/0/1.png", provider.tileUrl(1, 0, 1))
        assertTrue(!provider.needsApiKey())
    }

    @Test
    fun `an empty key leaves the placeholder in place rather than dropping the query`() {
        // The build refuses this case before publishing (ci/check-tile-provider.py), and
        // the screen shows whatever the host returns for the literal URL. What must not
        // happen is a URL assembled from half a template.
        val provider = TileServerMapProvider(
            id = "maptiler",
            tileTemplate = "https://api.example.org/{z}/{x}/{y}.png?key={key}",
            attribution = "© Example",
            apiKey = "",
        )

        assertEquals("https://api.example.org/1/0/1.png?key={key}", provider.tileUrl(1, 0, 1))
        assertTrue(provider.needsApiKey())
    }

    @Test
    fun `the zoom ceiling comes from the provider, not from a constant`() {
        val provider = MapProviders.fromConfig(
            tileTemplate = template,
            attribution = "© Example tiles",
            id = "shallow",
            maxZoom = 15,
        )

        assertEquals(15, provider.maxZoom)
        assertTrue(provider.tileUrl(19, 1, 1).startsWith("https://tiles.example.org/15/"))
    }

    @Test
    fun `the configured provider id travels with the provider`() {
        val provider = MapProviders.fromConfig(
            tileTemplate = template,
            attribution = "© Example tiles",
            id = "maptiler",
        )

        assertEquals("maptiler", provider.id)
    }

    @Test
    fun `the public osm host is recognisable, because a release must not use it`() {
        assertTrue(TileServerMapProvider.pointsAtPublicOsm(TileServerMapProvider.OSM_TEMPLATE))
        assertTrue(!TileServerMapProvider.pointsAtPublicOsm(template))
        assertTrue(TileServerMapProvider.isTemplateUsable(TileServerMapProvider.OSM_TEMPLATE))
        assertTrue(!TileServerMapProvider.isTemplateUsable("https://example.org/tiles.png"))
    }

    @Test
    fun `different tiles produce different urls, so nothing collides in the cache`() {
        val provider = provider()

        assertNotEquals(provider.tileUrl(4, 1, 1), provider.tileUrl(4, 2, 1))
        assertNotEquals(provider.tileUrl(4, 1, 1), provider.tileUrl(4, 1, 2))
        assertNotEquals(provider.tileUrl(4, 1, 1), provider.tileUrl(5, 1, 1))
    }

    private fun provider(maxZoom: Int = TileServerMapProvider.DEFAULT_MAX_ZOOM) =
        TileServerMapProvider(
            id = "example",
            tileTemplate = template,
            attribution = "© Example tiles",
            maxZoom = maxZoom,
        )
}
