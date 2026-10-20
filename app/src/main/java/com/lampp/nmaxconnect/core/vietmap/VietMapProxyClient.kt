package com.lampp.nmaxconnect.core.vietmap

import android.util.Log
import com.lampp.nmaxconnect.BuildConfig
import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.navigation.MainRoads
import com.lampp.nmaxconnect.core.navigation.Route
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Calls VietMap v4 through the Cloudflare Worker (`proxy/vietmap/`), which holds the API key.
 * The app only knows the proxy URL and the app token.
 */
class VietMapProxyClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlaceSearchApi, RouteApi {
    private val baseUrl = BuildConfig.VIETMAP_PROXY_URL.toHttpUrlOrNull()
    private val token = BuildConfig.VIETMAP_PROXY_TOKEN

    override val isConfigured: Boolean get() = baseUrl != null && token.isNotBlank()

    override suspend fun autocomplete(text: String, focus: GeoPoint?): List<PlaceSuggestion> =
        get("autocomplete", listOfNotNull("text" to text, focus?.let { "focus" to it.toQuery() }, "display_type" to "6"))

    override suspend fun place(refId: String): PlaceDetail = get("place", listOf("refid" to refId))

    override suspend fun reverse(point: GeoPoint): List<PlaceSuggestion> =
        get("reverse", listOf("lat" to point.lat.toString(), "lng" to point.lng.toString(), "display_type" to "6"))

    // "xăng" + cats=10009 lists every nearby station, nearest first (probed live 2026-10-17).
    override suspend fun gasStations(at: GeoPoint, radiusM: Int): List<PlaceSuggestion> = get(
        "autocomplete",
        listOf(
            "text" to "xăng",
            "focus" to at.toQuery(),
            "display_type" to "6",
            "cats" to "10009",
            "circle_center" to at.toQuery(),
            "circle_radius" to radiusM.toString(),
        ),
    )

    private val picker = RoutePicker({ points, extra -> route(points, extra) }) { Log.i(TAG, it) }

    override suspend fun route(from: GeoPoint, to: GeoPoint, headingDeg: Float?): Route = picker.route(from, to, headingDeg)

    override suspend fun routes(from: GeoPoint, to: GeoPoint): List<Route> = picker.routes(from, to)

    /** Motorcycle unless [extra] sets `vehicle`. */
    private suspend fun route(points: List<GeoPoint>, extra: List<Pair<String, String>>): List<Route> =
        get<RouteResponseDto>(
            "route",
            points.map { "point" to it.toQuery() } + listOf(
                "points_encoded" to "false",
                "annotations" to "congestion",
            ) + extra,
        ).toRoutes()

    private suspend inline fun <reified T> get(endpoint: String, params: List<Pair<String, String>>): T {
        val base = baseUrl ?: throw VietMapException("VietMap proxy not configured")
        val request = Request.Builder()
            .url(proxyUrl(base, endpoint, params))
            .header("X-App-Token", token)
            .build()
        val body = http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw VietMapException("VietMap $endpoint failed: HTTP ${response.code}", response.code)
            withContext(ioDispatcher) { response.body?.string() } ?: throw VietMapException("Empty $endpoint response")
        }
        return try {
            json.decodeFromString<T>(body)
        } catch (e: IllegalArgumentException) { // SerializationException extends it
            throw VietMapException("Unexpected $endpoint response: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VietMap"

        /** `{base}/{endpoint}?k=v…`; OkHttp encodes values, so ref_ids pass through unchanged. */
        fun proxyUrl(base: HttpUrl, endpoint: String, params: List<Pair<String, String>>): HttpUrl =
            base.newBuilder().addPathSegment(endpoint).apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
    }
}

/** Cancelling the coroutine cancels the HTTP call (autocomplete drops stale requests this way). */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, value, _ -> value.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

/**
 * Chooses between the checked main-road route (MainRoads) and the motorcycle one on top of [request], one VietMap
 * route call (motorcycle unless the params set `vehicle`). Kept apart from HTTP so the fallbacks are unit-tested.
 */
internal class RoutePicker(
    private val request: suspend (points: List<GeoPoint>, extra: List<Pair<String, String>>) -> List<Route>,
    private val log: (String) -> Unit,
) {
    /** Reroute: the main-road route only if it's checked within [REROUTE_MAIN_ROADS_MS], so it never holds up the moto one for long. */
    suspend fun route(from: GeoPoint, to: GeoPoint, headingDeg: Float?): Route = coroutineScope {
        val heading = listOfNotNull(headingDeg?.let { "heading" to it.toInt().toString() })
        val moto = async { request(listOf(from, to), heading).first() }
        withTimeoutOrNull(REROUTE_MAIN_ROADS_MS) { mainRoads(from, to, heading) { moto.await() } } ?: moto.await()
    }

    // Alternatives cost the same: billing counts waypoints, not returned paths (vietmap skill, Cost).
    suspend fun routes(from: GeoPoint, to: GeoPoint): List<Route> = coroutineScope {
        val moto = async { request(listOf(from, to), listOf("alternative" to "true")) }
        listOfNotNull(mainRoads(from, to, emptyList()) { moto.await().first() }) + moto.await()
    }

    /** The checked car-profile route, or null to ride the motorcycle one. Runs alongside [moto]. */
    private suspend fun mainRoads(from: GeoPoint, to: GeoPoint, extra: List<Pair<String, String>>, moto: suspend () -> Route): Route? =
        try {
            val car = request(listOf(from, to), extra + listOf("vehicle" to "car", "avoid" to "motorway")).first()
            MainRoads.checkpoints(car)?.let { vias ->
                MainRoads.accept(car, request(listOf(from) + vias + to, extra).first(), moto())
            }
        } catch (e: IOException) { // a via on a road closed to motorbikes fails the check call
            log("main-road route skipped: ${e.message}")
            null
        }

    companion object {
        const val REROUTE_MAIN_ROADS_MS = 1_500L
    }
}
