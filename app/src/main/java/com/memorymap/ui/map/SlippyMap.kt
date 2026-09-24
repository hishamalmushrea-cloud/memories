package com.memorymap.ui.map

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.memorymap.domain.map.MapProvider
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.usecase.MapCluster
import com.memorymap.util.geo.WebMercator
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A slippy tile map drawn with Compose.
 *
 * Tiles are fetched by Coil, which is already in the app for photos, so the
 * tiles get the same memory and disk cache: panning back over ground you have
 * already seen costs no network. Nothing here depends on a map SDK, so nothing
 * here can be stranded by one.
 *
 * Only the tiles inside the viewport are requested. Rows outside the projection
 * are skipped instead of fetched, and columns wrap, so crossing the antimeridian
 * works rather than producing a blank edge.
 */
@Composable
fun SlippyMap(
    provider: MapProvider,
    center: GeoPoint,
    zoom: Double,
    clusters: List<MapCluster>,
    userLocation: GeoPoint?,
    onCenterChange: (GeoPoint) -> Unit,
    onZoomChange: (Double) -> Unit,
    onClusterClick: (MapCluster) -> Unit,
    modifier: Modifier = Modifier,
    onTap: ((GeoPoint) -> Unit)? = null,
) {
    val density = LocalDensity.current

    // Gestures run in a long-lived coroutine, so they must read the newest
    // camera values rather than the ones captured when input started.
    val currentCenter by rememberUpdatedState(center)
    val currentZoom by rememberUpdatedState(zoom)
    val currentProvider by rememberUpdatedState(provider)

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val camera = cameraOf(currentCenter, currentZoom, currentProvider)
                    val nextZoom = (currentZoom + ln(zoomChange.toDouble()) / ln(2.0))
                        .coerceIn(currentProvider.minZoom.toDouble(), currentProvider.maxZoom.toDouble())
                    // Re-project the pan at the new zoom, so pinch-zooming keeps
                    // the point under the fingers instead of drifting away.
                    val nextScale = 2.0.pow(nextZoom - camera.tileZoom)
                    val nextX = camera.centerWorldX / camera.scale * nextScale - pan.x
                    val nextY = camera.centerWorldY / camera.scale * nextScale - pan.y
                    onCenterChange(
                        GeoPoint(
                            latitude = WebMercator.yToLatitude(nextY / nextScale, camera.tileZoom),
                            longitude = WebMercator.xToLongitude(nextX / nextScale, camera.tileZoom),
                        ),
                    )
                    onZoomChange(nextZoom)
                }
            }
            .then(
                if (onTap == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val camera = cameraOf(currentCenter, currentZoom, currentProvider)
                            val worldX = (offset.x - size.width / 2f + camera.centerWorldX) / camera.scale
                            val worldY = (offset.y - size.height / 2f + camera.centerWorldY) / camera.scale
                            onTap(
                                GeoPoint(
                                    latitude = WebMercator.yToLatitude(worldY, camera.tileZoom),
                                    longitude = WebMercator.xToLongitude(worldX, camera.tileZoom),
                                ),
                            )
                        }
                    }
                },
            ),
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        if (viewportWidth <= 0f || viewportHeight <= 0f) return@BoxWithConstraints

        val camera = cameraOf(center, zoom, provider)
        val tilePx = WebMercator.TILE_SIZE * camera.scale

        val originX = camera.centerWorldX - viewportWidth / 2f
        val originY = camera.centerWorldY - viewportHeight / 2f

        val firstX = floor(originX / tilePx).toInt()
        val lastX = floor((originX + viewportWidth) / tilePx).toInt()
        val firstY = floor(originY / tilePx).toInt()
        val lastY = floor((originY + viewportHeight) / tilePx).toInt()

        val tileDp = with(density) { tilePx.toDp() }

        for (ty in firstY..lastY) {
            if (!WebMercator.isValidTileY(camera.tileZoom, ty)) continue
            for (tx in firstX..lastX) {
                val left = (tx * tilePx - originX).roundToInt()
                val top = (ty * tilePx - originY).roundToInt()
                AsyncImage(
                    model = provider.tileUrl(camera.tileZoom, tx, ty),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset { IntOffset(left, top) }
                        .size(tileDp),
                )
            }
        }

        userLocation?.let { location ->
            MapPin(
                worldX = WebMercator.longitudeToX(location.longitude, camera.tileZoom) * camera.scale,
                worldY = WebMercator.latitudeToY(location.latitude, camera.tileZoom) * camera.scale,
                originX = originX,
                originY = originY,
                color = MaterialTheme.colorScheme.tertiary,
                label = null,
                onClick = null,
            )
        }

        clusters.forEach { cluster ->
            MapPin(
                worldX = WebMercator.longitudeToX(cluster.longitude, camera.tileZoom) * camera.scale,
                worldY = WebMercator.latitudeToY(cluster.latitude, camera.tileZoom) * camera.scale,
                originX = originX,
                originY = originY,
                color = MaterialTheme.colorScheme.primary,
                label = if (cluster.isSingle) null else cluster.size.toString(),
                onClick = { onClusterClick(cluster) },
            )
        }
    }
}

@Composable
private fun MapPin(
    worldX: Double,
    worldY: Double,
    originX: Float,
    originY: Float,
    color: Color,
    label: String?,
    onClick: (() -> Unit)?,
) {
    val left = (worldX - originX).toFloat().roundToInt()
    val top = (worldY - originY).toFloat().roundToInt()
    Box(
        modifier = Modifier
            .offset { IntOffset(left, top) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick ?: {},
            enabled = onClick != null,
            shape = CircleShape,
            color = color,
            modifier = Modifier
                .size(if (label == null) 14.dp else 30.dp)
                .clip(CircleShape),
        ) {
            if (label != null) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/** The projection state one frame of drawing or one gesture needs. */
private class MapCamera(
    val tileZoom: Int,
    val scale: Double,
    val centerWorldX: Double,
    val centerWorldY: Double,
)

/**
 * Picks the integer tile level and the scale factor that turns those tiles into
 * the fractional zoom the user is at.
 */
private fun cameraOf(center: GeoPoint, zoom: Double, provider: MapProvider): MapCamera {
    val tileZoom = floor(zoom).toInt().coerceIn(provider.minZoom, provider.maxZoom)
    val scale = 2.0.pow((zoom - tileZoom).coerceIn(-1.0, 1.0))
    return MapCamera(
        tileZoom = tileZoom,
        scale = scale,
        centerWorldX = WebMercator.longitudeToX(center.longitude, tileZoom) * scale,
        centerWorldY = WebMercator.latitudeToY(center.latitude, tileZoom) * scale,
    )
}
