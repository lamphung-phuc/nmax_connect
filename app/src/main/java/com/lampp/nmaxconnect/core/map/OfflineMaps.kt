package com.lampp.nmaxconnect.core.map

import android.content.Context
import android.util.Log
import com.lampp.nmaxconnect.core.di.ApplicationScope
import com.lampp.nmaxconnect.core.di.MainDispatcher
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.navigation.haversine
import com.mapbox.geojson.MultiPolygon
import com.mapbox.geojson.Point
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.vietmap.vietmapsdk.offline.OfflineGeometryRegionDefinition
import vn.vietmap.vietmapsdk.offline.OfflineManager
import vn.vietmap.vietmapsdk.offline.OfflineRegion
import vn.vietmap.vietmapsdk.offline.OfflineRegionError
import vn.vietmap.vietmapsdk.offline.OfflineRegionStatus

/** The rider's saved home area (Settings). */
sealed interface AreaMap {
    /** Not read from disk yet: shown as neither saved nor missing, so the row doesn't blink. */
    data object Unknown : AreaMap
    data object None : AreaMap
    data class Downloading(val percent: Int) : AreaMap
    data class Saved(val bytes: Long) : AreaMap
}

/**
 * Offline VietMap regions, so the dash map keeps drawing through dead zones:
 * - [prefetch]: the map along a route, when guidance starts or reroutes;
 * - [saveArea]: a [AREA_RADIUS_M] square around the rider, from Settings, kept until deleted.
 *
 * VietMap's vector tiles stop at z15 (overzoomed above, checked 2026-10-18) and day/night styles share them, so
 * both styles are saved at z12–15 (25 tiles = 1 VietMap transaction). Route regions are kept (newest
 * [KEEP_ROUTES]) instead of deleted per trip: the first one also fetches the styles' glyph ranges (6 fonts), and
 * deleting the last region would throw those away. Metadata tells them apart: a timestamp for a route, [AREA_TAG].
 */
@Singleton
class OfflineMaps @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @MainDispatcher private val main: CoroutineDispatcher,
) {
    private val _area = MutableStateFlow<AreaMap>(AreaMap.Unknown)
    val area: StateFlow<AreaMap> = _area.asStateFlow()

    /** Latest status per area region; [areaGen] drops callbacks from regions a newer save or delete replaced. */
    private val areaProgress = mutableMapOf<Long, OfflineRegionStatus>()
    private var areaGen = 0

    fun prefetch(route: List<GeoPoint>) {
        if (!VietMapStyle.available || route.size < 2) return
        val area = corridorBoxes(route).toMultiPolygon()
        withRegions { offline, regions ->
            regions.filter { it.tag.toLongOrNull() != null }
                .sortedByDescending { it.tag.toLong() }
                .drop((KEEP_ROUTES - 1) * STYLES.size)
                .forEach { it.delete(LogDelete) }
            create(offline, area, System.currentTimeMillis().toString(), ::logWhenSaved)
        }
    }

    /** Reads what's saved on disk and resumes an area download a restart cut short. */
    fun refreshArea() = withRegions { _, regions ->
        val saved = regions.filter { it.tag == AREA_TAG }
        val gen = resetArea(if (saved.isEmpty()) AreaMap.None else _area.value)
        saved.forEach { region ->
            region.getStatus(
                object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        // a complete one isn't re-activated: that would revalidate every tile
                        if (status?.isComplete == true) areaStatus(region, status, gen) else observeArea(region, gen)
                    }

                    override fun onError(error: String?) {
                        Log.w(TAG, "area status: $error")
                    }
                },
            )
        }
    }

    /** Replaces the saved area with one around [center]. */
    fun saveArea(center: GeoPoint) = withRegions { offline, regions ->
        deleteAreas(regions)
        val gen = resetArea(AreaMap.Downloading(0))
        val box = corridorBoxes(listOf(center, center), padMeters = AREA_RADIUS_M)
        create(offline, box.toMultiPolygon(), AREA_TAG) { observeArea(it, gen) }
    }

    fun deleteArea() = withRegions { _, regions ->
        deleteAreas(regions)
        resetArea(AreaMap.None)
    }

    private fun deleteAreas(regions: List<OfflineRegion>) = regions.filter { it.tag == AREA_TAG }.forEach {
        it.setDownloadState(OfflineRegion.STATE_INACTIVE)
        it.delete(LogDelete)
    }

    private fun resetArea(state: AreaMap): Int {
        areaProgress.clear()
        _area.value = state
        return ++areaGen
    }

    /** The offline API is main-thread only. */
    private fun withRegions(block: (OfflineManager, List<OfflineRegion>) -> Unit) {
        scope.launch(main) {
            val offline = OfflineManager.getInstance(context)
            offline.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) = block(offline, offlineRegions.orEmpty().toList())

                    override fun onError(error: String) {
                        Log.w(TAG, "regions not listed: $error")
                        block(offline, emptyList())
                    }
                },
            )
        }
    }

    private fun create(offline: OfflineManager, area: MultiPolygon, tag: String, onCreated: (OfflineRegion) -> Unit) {
        for (style in STYLES) {
            val definition = OfflineGeometryRegionDefinition(
                VietMapStyle.url(style),
                area,
                MIN_ZOOM,
                MAX_ZOOM,
                PIXEL_RATIO,
                false,
            )
            offline.createOfflineRegion(
                definition,
                tag.encodeToByteArray(),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) = onCreated(offlineRegion)

                    override fun onError(error: String) {
                        Log.w(TAG, "region not created: $error")
                    }
                },
            )
        }
    }

    private fun logWhenSaved(region: OfflineRegion) = download(region) { status ->
        if (status.isComplete) {
            Log.i(TAG, "route map saved: ${status.completedResourceCount} resources, ${status.completedResourceSize / 1024} KB")
        }
    }

    private fun observeArea(region: OfflineRegion, gen: Int) = download(region) { areaStatus(region, it, gen) }

    private fun areaStatus(region: OfflineRegion, status: OfflineRegionStatus, gen: Int) {
        if (gen != areaGen) return
        areaProgress[region.id] = status
        val all = areaProgress.values
        val complete = all.all { it.isComplete }
        // On a refresh the styles report one by one: one complete style isn't a 99% download.
        if (complete && all.size < STYLES.size && _area.value !is AreaMap.Downloading) return
        _area.value = if (complete && all.size == STYLES.size) {
            AreaMap.Saved(all.sumOf { it.completedResourceSize })
        } else {
            val required = all.sumOf { it.requiredResourceCount }.coerceAtLeast(1)
            AreaMap.Downloading((all.sumOf { it.completedResourceCount } * 100 / required).toInt().coerceIn(0, 99))
        }
    }

    /** Downloads [region] (a complete one just reports its status) and stops once complete. */
    private fun download(region: OfflineRegion, onStatus: (OfflineRegionStatus) -> Unit) {
        region.setObserver(
            object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    onStatus(status)
                }

                override fun onError(error: OfflineRegionError) {
                    Log.w(TAG, "map download: ${error.reason} ${error.message}") // the SDK retries by itself
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    Log.w(TAG, "map download over the $limit tile limit; stopped")
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                }
            },
        )
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private val OfflineRegion.tag get() = metadata.decodeToString()

    private object LogDelete : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit

        override fun onError(error: String) {
            Log.w(TAG, "old map region not deleted: $error")
        }
    }

    private companion object {
        const val TAG = "OfflineMaps"
        val STYLES = listOf(VietMapStyle.DAY, VietMapStyle.NIGHT)
        const val KEEP_ROUTES = 5
        const val AREA_TAG = "area"
        const val AREA_RADIUS_M = 15_000.0 // a 30 km square: most of a city
        const val MIN_ZOOM = 12.0
        const val MAX_ZOOM = 15.0 // the tile source's maxzoom
        const val PIXEL_RATIO = 1f // the dash renders at 160 dpi
    }
}

data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double)

private fun List<GeoBox>.toMultiPolygon(): MultiPolygon = MultiPolygon.fromLngLats(
    map { b ->
        val ring = listOf(b.west to b.south, b.east to b.south, b.east to b.north, b.west to b.north, b.west to b.south)
        listOf(ring.map { (lng, lat) -> Point.fromLngLat(lng, lat) })
    },
)

/**
 * Boxes around consecutive ~[chunkMeters] pieces of the line, padded by [padMeters]: a corridor a long route
 * can't blow up the way one bounding box would. Each segment's two ends share a box, so the whole line is covered.
 */
fun corridorBoxes(points: List<GeoPoint>, chunkMeters: Double = 1_000.0, padMeters: Double = 300.0): List<GeoBox> {
    val boxes = mutableListOf<GeoBox>()
    var chunk = mutableListOf(points.first())
    var length = 0.0
    for (i in 1 until points.size) {
        chunk += points[i]
        length += haversine(points[i - 1], points[i])
        if (length >= chunkMeters || i == points.lastIndex) {
            val padLat = padMeters / M_PER_DEG
            val padLng = padMeters / (M_PER_DEG * cos(Math.toRadians(chunk.first().lat)))
            boxes += GeoBox(
                chunk.minOf { it.lat } - padLat,
                chunk.minOf { it.lng } - padLng,
                chunk.maxOf { it.lat } + padLat,
                chunk.maxOf { it.lng } + padLng,
            )
            chunk = mutableListOf(points[i])
            length = 0.0
        }
    }
    return boxes
}

private const val M_PER_DEG = 111_320.0
