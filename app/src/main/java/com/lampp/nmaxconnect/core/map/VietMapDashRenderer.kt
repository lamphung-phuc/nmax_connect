package com.lampp.nmaxconnect.core.map

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.MainThread
import androidx.core.graphics.createBitmap
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.settings.DashStat
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import java.io.ByteArrayOutputStream
import java.io.Closeable
import vn.vietmap.vietmapsdk.Vietmap
import vn.vietmap.vietmapsdk.camera.CameraPosition
import vn.vietmap.vietmapsdk.camera.CameraUpdateFactory
import vn.vietmap.vietmapsdk.geometry.LatLng
import vn.vietmap.vietmapsdk.maps.MapView
import vn.vietmap.vietmapsdk.maps.Style
import vn.vietmap.vietmapsdk.maps.VietMapGL
import vn.vietmap.vietmapsdk.maps.VietMapGLOptions
import vn.vietmap.vietmapsdk.style.layers.LineLayer
import vn.vietmap.vietmapsdk.style.layers.Property
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory
import vn.vietmap.vietmapsdk.style.sources.GeoJsonSource

object VietMapStyle {
    const val DAY = "tm"
    const val NIGHT = "dm"

    fun url(code: String, tileKey: String) = "https://maps.vietmap.vn/maps/styles/$code/style.json?apikey=$tileKey"
}

data class DashCamera(val lat: Double, val lng: Double, val bearing: Double, val zoom: Double, val tilt: Double)

/**
 * Renders a VietMap MapView offscreen at the dash resolution, the way Garmin StreetCross does:
 * private VirtualDisplay → Presentation hosting the MapView → ImageReader → bitmap → JPEG.
 *
 * Create and drive it on the main thread; [captureJpeg] is safe from any thread.
 */
class VietMapDashRenderer @MainThread constructor(
    context: Context,
    private val display: DisplayModel,
    styleUrl: String,
) : Closeable {
    private val captureThread = HandlerThread("dash-capture").apply { start() }
    private val reader = ImageReader.newInstance(display.width, display.height, PixelFormat.RGBA_8888, 2)
    private val virtualDisplay: VirtualDisplay
    private val presentation: Presentation
    private val mapView: MapView
    private var map: VietMapGL? = null
    private var pendingCamera: DashCamera? = null
    private var lastCamera: DashCamera? = null
    private var route: List<GeoPoint>? = null
    private var ahead: List<GeoPoint>? = null
    private val hud = DashHud()

    @Volatile private var hasPosition = false

    @Volatile private var mapReady = false

    @Volatile private var guidance: DashGuidance? = null

    @Volatile private var stats: LocalRecord? = null

    @Volatile private var statItems: Set<DashStat> = emptySet()

    private val lock = Any()
    private var scratch: Bitmap? = null // ImageReader rows may be wider than the display (row stride padding)
    private val frame: Bitmap = createBitmap(display.width, display.height)
    private val frameCanvas = Canvas(frame)
    private var frameId = 0L
    private var jpegFrameId = -1L
    private var jpeg: ByteArray? = null
    private val jpegOut = ByteArrayOutputStream(64 * 1024)

    init {
        Vietmap.getInstance(context.applicationContext)
        virtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "nmax-dash",
            display.width,
            display.height,
            DASH_DENSITY_DPI,
            reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        presentation = Presentation(context, virtualDisplay.display)
        mapView = MapView(
            presentation.context,
            VietMapGLOptions.createFromAttributes(presentation.context).compassEnabled(false).textureMode(true),
        )
        presentation.setContentView(mapView)
        mapView.onCreate(null)
        presentation.show()
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.setAllGesturesEnabled(false)
            m.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                drawRoute(style)
                // tiles that never finish (offline) mustn't hold the stream back
                mapView.postDelayed({ mapReady = true }, READY_FALLBACK_MS)
            }
            pendingCamera?.let(::moveCamera)
        }
        mapView.addOnDidFinishRenderingMapListener { fully -> if (fully) mapReady = true }
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use(::copyFrame) }, Handler(captureThread.looper))
    }

    /** [durationMs] > 0 eases there (smooth between 1 Hz GPS fixes); 0 jumps. */
    @MainThread
    fun moveCamera(camera: DashCamera, durationMs: Int = 0) {
        hasPosition = true
        lastCamera = camera
        val m = map ?: run { pendingCamera = camera; return }
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(camera.lat, camera.lng))
                .bearing(camera.bearing)
                .zoom(camera.zoom)
                .tilt(camera.tilt)
                .padding(columnLeft().toDouble(), 0.0, 0.0, 0.0) // centre the map in the area right of the stats column
                .build(),
        )
        if (durationMs > 0) m.easeCamera(update, durationMs) else m.moveCamera(update)
    }

    @MainThread
    fun setStyle(styleUrl: String) {
        map?.setStyle(Style.Builder().fromUri(styleUrl), ::drawRoute)
    }

    /**
     * Route line on the map; null clears it. [ahead] (the part still to ride) is blue over the whole
     * route in grey, so the passed part greys out; null shows the whole route blue. Survives style changes.
     */
    @MainThread
    fun setRoute(points: List<GeoPoint>?, ahead: List<GeoPoint>? = null) {
        val changed = points !== route
        route = points
        this.ahead = ahead
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return
        if (changed) drawLine(style, PASSED_ID, PASSED_COLOR, route)
        drawLine(style, ROUTE_ID, ROUTE_COLOR, ahead ?: route)
    }

    /** Next-turn panel drawn into the frame; null hides it. */
    @MainThread
    fun setGuidance(g: DashGuidance?) {
        if (g == guidance) return
        val columnBefore = columnLeft()
        guidance = g
        if (columnLeft() != columnBefore) lastCamera?.let(::moveCamera)
        synchronized(lock) { composeFrame() }
    }

    /** Bike stats column drawn into the frame showing [items]; null hides it. */
    @MainThread
    fun setStats(s: LocalRecord?, items: Set<DashStat>) {
        if (s == stats && items == statItems) return
        val columnBefore = columnLeft()
        stats = s
        statItems = items
        if (columnLeft() != columnBefore) lastCamera?.let(::moveCamera)
        synchronized(lock) { composeFrame() }
    }

    private fun drawRoute(style: Style) {
        drawLine(style, PASSED_ID, PASSED_COLOR, route) // added first, so it stays under the blue line
        drawLine(style, ROUTE_ID, ROUTE_COLOR, ahead ?: route)
    }

    private fun drawLine(style: Style, id: String, color: String, points: List<GeoPoint>?) {
        val line = points?.takeIf { it.size >= 2 }?.let { pts -> LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) }) }
        val source = style.getSourceAs<GeoJsonSource>(id)
        when {
            source == null && line == null -> Unit
            source == null -> {
                style.addSource(GeoJsonSource(id, line))
                style.addLayer(
                    LineLayer(id, id).withProperties(
                        PropertyFactory.lineColor(color),
                        PropertyFactory.lineWidth(ROUTE_WIDTH),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
            }
            line == null -> source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            else -> source.setGeoJson(line)
        }
    }

    /** Latest rendered frame as JPEG, re-encoded only when the map drew something new; null before the first frame. */
    fun captureJpeg(quality: Int = 80): ByteArray? = synchronized(lock) {
        if (frameId == 0L) return null
        if (frameId != jpegFrameId) {
            jpegOut.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)
            jpeg = jpegOut.toByteArray()
            jpegFrameId = frameId
        }
        jpeg
    }

    private fun copyFrame(image: android.media.Image) {
        if (!mapReady) return // frames before the first full render are a blank map
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        synchronized(lock) {
            val src = scratch?.takeIf { it.width == rowPixels } ?: createBitmap(rowPixels, display.height).also { scratch = it }
            src.copyPixelsFromBuffer(plane.buffer)
            composeFrame()
        }
    }

    /** Map + overlays into [frame]; caller holds [lock]. */
    private fun composeFrame() {
        val src = scratch ?: return
        frameCanvas.drawBitmap(src, 0f, 0f, null)
        if (hasPosition) hud.drawPuck(frameCanvas, columnLeft())
        hud.drawPanel(frameCanvas, guidance, stats, statItems)
        frameId++
    }

    /** The left column shows whenever there are stats or a turn to draw. */
    private fun columnLeft() = if (stats != null || guidance != null) DashHud.COLUMN_W else 0f

    @MainThread
    override fun close() {
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
        presentation.dismiss()
        virtualDisplay.release()
        // Let an in-flight copyFrame finish first: reading an Image whose reader is closed throws on the capture thread.
        reader.setOnImageAvailableListener(null, null)
        captureThread.quitSafely()
        captureThread.join()
        reader.close()
    }

    private companion object {
        // shortcut: mdpi keeps map labels readable on the ~4" dash; tune after seeing it on the bike.
        const val DASH_DENSITY_DPI = 160
        const val ROUTE_ID = "nmax-route" // source and layer
        const val PASSED_ID = "nmax-route-passed"
        const val ROUTE_COLOR = "#1A73E8" // map layer paint, not app UI: the dash has no Material theme
        const val PASSED_COLOR = "#9AA0A6"
        const val ROUTE_WIDTH = 6f
        const val READY_FALLBACK_MS = 3_000L
    }
}
