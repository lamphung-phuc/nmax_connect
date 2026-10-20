package com.lampp.nmaxconnect.core.vietmap

import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.navigation.Congestion
import com.lampp.nmaxconnect.core.navigation.RawInstruction
import com.lampp.nmaxconnect.core.navigation.Route
import com.lampp.nmaxconnect.core.navigation.TrafficSpan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Route v4 via the proxy (docs/vietmap-agent-docs/routing.txt). The proxy forces vehicle=motorcycle. */
interface RouteApi {
    val isConfigured: Boolean

    /** [headingDeg] = current travel direction, so reroutes don't start with a U-turn. */
    suspend fun route(from: GeoPoint, to: GeoPoint, headingDeg: Float? = null): Route

    /** Route v4 `alternative=true`: the recommended route first, then up to two alternatives (checked live: 3 paths). */
    suspend fun routes(from: GeoPoint, to: GeoPoint): List<Route>
}

// Real shape (checked against the live API): with points_encoded=false, `points` is a GeoJSON LineString.
@Serializable
internal data class RouteResponseDto(
    val code: String = "",
    val messages: JsonElement? = null,
    val paths: List<RoutePathDto> = emptyList(),
)

@Serializable
internal data class RoutePathDto(
    val distance: Double,
    val time: Long,
    val points: LineStringDto,
    val instructions: List<InstructionDto> = emptyList(),
    val annotations: AnnotationsDto? = null,
)

/** `annotations=congestion`: `{"congestion":[{"value":"heavy","first":2,"last":5},…]}`, indices into the geometry. */
@Serializable
internal data class AnnotationsDto(val congestion: List<CongestionDto> = emptyList())

@Serializable
internal data class CongestionDto(val value: String, val first: Int, val last: Int)

@Serializable
internal data class LineStringDto(val coordinates: List<List<Double>>)

@Serializable
internal data class InstructionDto(
    val distance: Double = 0.0,
    val sign: Int = 0,
    val text: String = "",
    val time: Long = 0,
    @SerialName("street_name") val streetName: String? = null,
)

internal fun RouteResponseDto.toRoutes(): List<Route> {
    if (code != "OK" || paths.isEmpty()) throw VietMapException("Route failed: $code ${messages ?: ""}".trim())
    return paths.map { path ->
        Route.of(
            // GeoJSON is [lng, lat]
            geometry = path.points.coordinates.map { GeoPoint(lat = it[1], lng = it[0]) },
            distanceMeters = path.distance,
            timeMs = path.time,
            instructions = path.instructions.map { RawInstruction(it.sign, it.text, it.streetName.orEmpty(), it.distance, it.time) },
            traffic = path.annotations?.congestion.orEmpty().mapNotNull { c ->
                Congestion.entries.firstOrNull { it.name.equals(c.value, ignoreCase = true) }?.let { TrafficSpan(c.first, c.last, it) }
            },
        )
    }
}
