package com.lampp.nmaxconnect.feature.navigate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.location.LocationManager
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.map.VietMapStyle
import com.lampp.nmaxconnect.core.navigation.Congestion
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.navigation.Route
import com.lampp.nmaxconnect.ui.theme.TrafficColors
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import kotlinx.coroutines.delay
import vn.vietmap.vietmapsdk.Vietmap
import vn.vietmap.vietmapsdk.camera.CameraPosition
import vn.vietmap.vietmapsdk.camera.CameraUpdateFactory
import vn.vietmap.vietmapsdk.geometry.LatLng
import vn.vietmap.vietmapsdk.geometry.LatLngBounds
import vn.vietmap.vietmapsdk.location.LocationComponentActivationOptions
import vn.vietmap.vietmapsdk.location.modes.CameraMode
import vn.vietmap.vietmapsdk.location.modes.RenderMode
import vn.vietmap.vietmapsdk.maps.MapView
import vn.vietmap.vietmapsdk.maps.Style
import vn.vietmap.vietmapsdk.maps.VietMapGL
import vn.vietmap.vietmapsdk.maps.VietMapGLOptions
import vn.vietmap.vietmapsdk.style.expressions.Expression
import vn.vietmap.vietmapsdk.style.layers.LineLayer
import vn.vietmap.vietmapsdk.style.layers.Property
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory
import vn.vietmap.vietmapsdk.style.layers.SymbolLayer
import vn.vietmap.vietmapsdk.style.sources.GeoJsonSource

/**
 * Interactive VietMap: GPS puck, destination pin, route options (tap a grey one to pick it) with
 * live congestion on the chosen one; always top-down (while guiding the screen shows no map). Long press drops a pin.
 * [recenter] changes = "my location" pressed; [bottomInsetPx] = height of the panel over the map's bottom.
 * The view is drawn [LOGO_HIDE] taller than its slot, so the VietMap logo sits just below the visible edge.
 */
@Composable
fun NavigateMap(
    holder: NavigateMapHolder,
    uiState: NavigateUiState,
    onAction: (NavigateUiAction) -> Unit,
    recenter: Int,
    bottomInsetPx: Int,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        return
    }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val hiddenPx = with(LocalDensity.current) { LOGO_HIDE.roundToPx() }
    val bottomPx = bottomInsetPx + hiddenPx
    val mapView = holder.view
    MapViewLifecycle(mapView)
    MapSetup(holder, dark)
    SideEffect { holder.shown = true }

    val map = holder.map
    val style = holder.style
    val state by rememberUpdatedState(uiState)
    val action by rememberUpdatedState(onAction)
    // The listeners outlive this composition (holder), so they read the latest values through these.
    SideEffect { holder.listener = { a -> action(a) }; holder.nav = { state.nav } }

    LaunchedEffect(style, uiState.locationGranted) {
        val s = style ?: return@LaunchedEffect
        if (uiState.locationGranted) map?.enableLocation(context, s)
    }

    // Route lines: re-set only when the route set or the choice changes, not on every GPS fix.
    val nav = uiState.nav
    val routes = when (nav) {
        is NavState.Preview -> nav.routes
        is NavState.Active -> listOf(nav.route)
        else -> emptyList()
    }
    val selected = (nav as? NavState.Preview)?.selected ?: 0
    val destination = when (nav) {
        is NavState.Routing -> nav.destination
        is NavState.Preview -> nav.destination
        is NavState.Active -> nav.destination
        is NavState.Failed -> nav.destination
        else -> uiState.selected
    }?.point
    LaunchedEffect(style, routes, selected, destination) {
        style?.drawNav(routes, selected, destination)
    }

    // Camera: frame the pin, then all route options above the panel. It re-frames when the panel's height
    // changes (route options come in after the routing panel), once the height has settled.
    LaunchedEffect(map, destination, routes, bottomPx) {
        val m = map ?: return@LaunchedEffect
        // While a route loads (e.g. after From/To swap) keep the map still; the new routes frame it.
        if (state.nav is NavState.Routing) return@LaunchedEffect
        val key = listOf(destination, routes, bottomPx)
        if (holder.cameraFor == key) return@LaunchedEffect // returning to the tab: leave the camera where it was
        delay(PANEL_SETTLE_MS) // the panel animates its height; a newer value restarts this effect
        holder.cameraFor = key
        val location = m.locationComponent.takeIf { it.isLocationComponentActivated }
        when {
            // Guiding on the phone (no dash): follow the rider like a navigation app.
            state.nav is NavState.Active -> {
                location?.cameraMode = CameraMode.TRACKING_GPS
                location?.zoomWhileTracking(GUIDE_ZOOM)
            }
            routes.isNotEmpty() -> {
                location?.cameraMode = CameraMode.NONE
                val bounds = LatLngBounds.Builder().includes(routes.flatMap { r -> r.geometry.map { LatLng(it.lat, it.lng) } }).build()
                val fit = m.getCameraForLatLngBounds(bounds, intArrayOf(FIT_PX, FIT_PX, FIT_PX, bottomPx + FIT_PX))
                // A very short route would fit at max zoom, where there is nothing left to see.
                fit?.let {
                    val camera = CameraPosition.Builder(it).zoom(minOf(it.zoom, MAX_FIT_ZOOM)).tilt(0.0).build()
                    m.animateCamera(CameraUpdateFactory.newCameraPosition(camera))
                }
            }
            destination != null -> {
                location?.cameraMode = CameraMode.NONE
                m.animateCamera(CameraUpdateFactory.newCameraPosition(placeCamera(destination, bottomPx)))
            }
            else -> location?.cameraMode = CameraMode.NONE
        }
    }

    LaunchedEffect(recenter) {
        if (recenter == holder.recentered) return@LaunchedEffect // the saved count comes back with the tab
        holder.recentered = recenter
        val m = map ?: return@LaunchedEffect
        val location = m.locationComponent.takeIf { it.isLocationComponentActivated } ?: return@LaunchedEffect
        val here = location.lastKnownLocation ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newCameraPosition(placeCamera(GeoPoint(here.latitude, here.longitude), bottomPx)))
    }

    Box(modifier.clipToBounds()) {
        AndroidView(
            // The retained view may still sit in the previous AndroidView's container.
            factory = { mapView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.layout { measurable, c ->
                val height = if (c.hasBoundedHeight) c.maxHeight + hiddenPx else c.maxHeight
                val placeable = measurable.measure(c.copy(minHeight = height, maxHeight = height))
                layout(c.maxWidth, c.maxHeight) { placeable.place(0, 0) } // overflow only at the bottom
            },
        )
    }
}

/**
 * Keeps one MapView (with its style, layers and camera) alive across tab switches: the NavHost disposes
 * the Navigate screen on every switch, and a new MapView meant a style reload and a camera reset.
 * Create it with [rememberNavigateMapHolder] above the NavHost.
 */
class NavigateMapHolder(private val context: Context) {
    private var created: MapView? = null

    // TextureView mode: survives being detached from one parent and attached to the next.
    val view: MapView
        get() = created ?: run {
            Vietmap.getInstance(context.applicationContext)
            MapView(context, VietMapGLOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
                .also { created = it }
        }

    internal var map by mutableStateOf<VietMapGL?>(null)
    internal var style by mutableStateOf<Style?>(null)
    internal var mapRequested = false

    /** Navigate has taken the view; [WarmNavigateMap] steps aside for good. */
    internal var shown by mutableStateOf(false)
    internal var styleDark: Boolean? = null
    internal var cameraFor: Any? = null
    internal var recentered = 0
    internal var listener: (NavigateUiAction) -> Unit = {}
    internal var nav: () -> NavState = { NavState.Idle }

    /** Map coordinate under a point of the map view (px from its top-left); null before the map is ready. */
    fun pointAt(x: Float, y: Float): GeoPoint? =
        map?.projection?.fromScreenLocation(android.graphics.PointF(x, y))?.let { GeoPoint(it.latitude, it.longitude) }

    internal fun destroy() {
        created?.onDestroy()
        created = null
        mapRequested = false
        map = null
        style = null
        styleDark = null
    }
}

/** Call it outside anything that is disposed on tab switches; destroys the map when it leaves composition. */
@Composable
fun rememberNavigateMapHolder(): NavigateMapHolder {
    val context = LocalContext.current
    val holder = remember(context) { NavigateMapHolder(context) }
    DisposableEffect(holder) { onDispose { holder.destroy() } }
    return holder
}

/**
 * Creates the map and loads its style a frame after launch, until the Navigate tab first takes the view:
 * opening it cold costs ~1 s (MapView creation blocks the main thread ~0.5 s, and the style is revalidated
 * online on every start, no cache headers, even with an offline area saved). Place it under the tabs.
 */
@Composable
fun WarmNavigateMap(holder: NavigateMapHolder) {
    if (LocalInspectionMode.current || holder.shown) return
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; warm = true }
    if (!warm) return
    val mapView = holder.view
    // Leaves only when Navigate takes the view (already started there): stopping it here would blank the map.
    MapViewLifecycle(mapView, stopOnDispose = false)
    MapSetup(holder, isSystemInDarkTheme())
    // The map only starts once its TextureView has a surface: attached, sized and drawn (not alpha 0).
    AndroidView(factory = { mapView.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = Modifier.size(1.dp))
}

/** Map + style + nav layers, once per holder (and again when [dark] flips); shared by [NavigateMap] and [WarmNavigateMap]. */
@Composable
private fun MapSetup(holder: NavigateMapHolder, dark: Boolean) {
    val colors = MapColors(
        route = MaterialTheme.colorScheme.primary,
        alternative = MaterialTheme.colorScheme.outline,
        casing = MaterialTheme.colorScheme.surfaceContainerLowest,
        pin = MaterialTheme.colorScheme.error, // red, like the destination pin in the To field
    )
    val mapView = holder.view
    LaunchedEffect(mapView) {
        if (holder.mapRequested) return@LaunchedEffect // back on the tab, or warmed up: same map, same camera
        holder.mapRequested = true
        mapView.getMapAsync { m ->
            // Open where the rider last was (its offline area), not over the whole country.
            val here = lastKnownLocation(mapView.context)
            m.cameraPosition = CameraPosition.Builder()
                .target(here ?: LatLng(VIETNAM.lat, VIETNAM.lng))
                .zoom(if (here != null) PLACE_ZOOM else 5.0)
                .build()
            m.uiSettings.isCompassEnabled = true
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(RectF(p.x - TAP_PX, p.y - TAP_PX, p.x + TAP_PX, p.y + TAP_PX), ALT_ID).firstOrNull()
                val index = hit?.getNumberProperty(INDEX)?.toInt() ?: return@addOnMapClickListener false
                holder.listener(NavigateUiAction.SelectRoute(index))
                true
            }
            m.addOnMapLongClickListener { latLng ->
                if (holder.nav() != NavState.Idle) return@addOnMapLongClickListener false
                holder.listener(NavigateUiAction.DropPin(GeoPoint(latLng.latitude, latLng.longitude)))
                true
            }
            holder.map = m
        }
    }

    val map = holder.map
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        if (holder.styleDark == dark) return@LaunchedEffect // already loaded (or loading) this style
        holder.styleDark = dark
        holder.style = null
        val url = VietMapStyle.url(if (dark) VietMapStyle.NIGHT else VietMapStyle.DAY)
        m.setStyle(Style.Builder().fromUri(url)) {
            it.addNavLayers(mapView.context, colors)
            holder.style = it
        }
    }
}

/** Forwards the host lifecycle to the MapView (vietmap skill, rule 7); [NavigateMapHolder] destroys it. */
@Composable
private fun MapViewLifecycle(mapView: MapView, stopOnDispose: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!stopOnDispose) return@onDispose
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
        }
    }
}

/** Newest cached fix of any provider; null without permission (the SecurityException is caught) or a fix. */
@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): LatLng? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return lm.getProviders(true)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { LatLng(it.latitude, it.longitude) }
}

private data class MapColors(val route: Color, val alternative: Color, val casing: Color, val pin: Color)

@SuppressLint("MissingPermission") // only called once locationGranted
private fun VietMapGL.enableLocation(context: android.content.Context, style: Style) {
    locationComponent.apply {
        if (!isLocationComponentActivated) activateLocationComponent(LocationComponentActivationOptions.builder(context, style).build())
        isLocationComponentEnabled = true
        renderMode = RenderMode.COMPASS
        // First fix of the session: start where the rider is instead of the whole country.
        if (cameraPosition.zoom < PLACE_ZOOM - 1) {
            lastKnownLocation?.let { moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), PLACE_ZOOM)) }
        }
    }
}

private fun placeCamera(p: GeoPoint, bottomPx: Int) = CameraPosition.Builder()
    .target(LatLng(p.lat, p.lng))
    .zoom(PLACE_ZOOM)
    .tilt(0.0)
    .bearing(0.0)
    .padding(0.0, 0.0, 0.0, bottomPx.toDouble())
    .build()

/** Sources + layers, bottom to top: alternatives, casing, route, traffic, pin. Re-added after every setStyle. */
private fun Style.addNavLayers(context: Context, c: MapColors) {
    val pinPx = (PIN_DP * context.resources.displayMetrics.density).toInt()
    val pin = ContextCompat.getDrawable(context, R.drawable.ic_map_destination)!!.apply { setTint(c.pin.toArgb()) }
    addImage(PIN_ID, pin.toBitmap(pinPx, pinPx))
    listOf(ALT_ID, CASING_ID, ROUTE_ID, TRAFFIC_ID, PIN_ID).forEach { addSource(GeoJsonSource(it)) }
    addLayer(line(ALT_ID, PropertyFactory.lineColor(c.alternative.toArgb()), ROUTE_WIDTH))
    addLayer(line(CASING_ID, PropertyFactory.lineColor(c.casing.toArgb()), ROUTE_WIDTH + 4f))
    addLayer(line(ROUTE_ID, PropertyFactory.lineColor(c.route.toArgb()), ROUTE_WIDTH))
    addLayer(
        line(
            TRAFFIC_ID,
            PropertyFactory.lineColor(
                Expression.match(
                    Expression.get(LEVEL),
                    Expression.color(c.route.toArgb()),
                    Expression.stop(Congestion.MODERATE.name, Expression.color(TrafficColors.Moderate.toArgb())),
                    Expression.stop(Congestion.HEAVY.name, Expression.color(TrafficColors.Heavy.toArgb())),
                    Expression.stop(Congestion.SEVERE.name, Expression.color(TrafficColors.Severe.toArgb())),
                ),
            ),
            ROUTE_WIDTH,
        ),
    )
    addLayer(
        // The pin's tip (bottom of its 24-unit box, minus 2 units of padding) sits on the destination.
        SymbolLayer(PIN_ID, PIN_ID).withProperties(
            PropertyFactory.iconImage(PIN_ID),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, PIN_DP / 12f)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
}

private fun line(id: String, color: vn.vietmap.vietmapsdk.style.layers.PropertyValue<*>, width: Float) =
    LineLayer(id, id).withProperties(
        color,
        PropertyFactory.lineWidth(width),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

private fun Style.drawNav(routes: List<Route>, selected: Int, destination: GeoPoint?) {
    val chosen = routes.getOrNull(selected)
    val alternatives = routes.mapIndexedNotNull { i, r ->
        if (i == selected) null else r.geometry.feature().apply { addNumberProperty(INDEX, i) }
    }
    set(ALT_ID, alternatives)
    set(CASING_ID, listOfNotNull(chosen?.geometry?.feature()))
    set(ROUTE_ID, listOfNotNull(chosen?.geometry?.feature()))
    set(
        TRAFFIC_ID,
        chosen?.traffic.orEmpty().filter { it.level != Congestion.LOW }.mapNotNull { span ->
            val end = (span.last + 1).coerceIn(0, chosen!!.geometry.size)
            chosen.geometry.subList(span.first.coerceIn(0, end), end)
                .takeIf { it.size >= 2 }?.feature()?.apply { addStringProperty(LEVEL, span.level.name) }
        },
    )
    set(PIN_ID, listOfNotNull(destination?.let { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) }))
}

private fun Style.set(id: String, features: List<Feature>) {
    getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

// GeoJSON is [lng, lat]
private fun List<GeoPoint>.feature(): Feature = Feature.fromGeometry(LineString.fromLngLats(map { Point.fromLngLat(it.lng, it.lat) }))

private val VIETNAM = GeoPoint(16.0, 106.0)
private val LOGO_HIDE = 40.dp // logo (~24 dp) + its bottom margin
private const val ALT_ID = "nav-alternatives"
private const val CASING_ID = "nav-route-casing"
private const val ROUTE_ID = "nav-route"
private const val TRAFFIC_ID = "nav-traffic"
private const val PIN_ID = "nav-pin"
private const val INDEX = "i"
private const val LEVEL = "level"
private const val ROUTE_WIDTH = 6f
private const val TAP_PX = 24f
private const val FIT_PX = 64
private const val PLACE_ZOOM = 16.0
private const val MAX_FIT_ZOOM = 17.0
private const val GUIDE_ZOOM = 17.0
private const val PANEL_SETTLE_MS = 150L
private const val PIN_DP = 40f
