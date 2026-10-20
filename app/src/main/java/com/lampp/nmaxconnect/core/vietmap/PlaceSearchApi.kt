package com.lampp.nmaxconnect.core.vietmap

import com.lampp.nmaxconnect.core.location.GeoPoint
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One Autocomplete v4 suggestion. Has no coordinates: resolve [refId] with Place v4 on selection. */
@Serializable
data class PlaceSuggestion(
    @SerialName("ref_id") val refId: String,
    val name: String = "",
    val address: String = "",
    val display: String = "",
    /** Kilometres from `focus`, when one was sent. */
    val distance: Double? = null,
)

/** Place v4 result. */
@Serializable
data class PlaceDetail(
    val display: String = "",
    val name: String = "",
    val address: String = "",
    val lat: Double,
    val lng: Double,
)

class VietMapException(message: String, val httpCode: Int? = null) : IOException(message)

/** Destination search (docs/vietmap-agent-docs/search-geocoding.txt), through the Cloudflare proxy. */
interface PlaceSearchApi {
    /** False until VIETMAP_PROXY_URL and VIETMAP_PROXY_TOKEN are set in local.properties. */
    val isConfigured: Boolean

    suspend fun autocomplete(text: String, focus: GeoPoint?): List<PlaceSuggestion>

    /** Pass the suggestion's ref_id verbatim. */
    suspend fun place(refId: String): PlaceDetail

    /** Reverse v4: addresses at [point], nearest first (`address` = "Phường 4,Quận 5,Thành Phố Hồ Chí Minh"). */
    suspend fun reverse(point: GeoPoint): List<PlaceSuggestion>

    /** Gas stations (Autocomplete v4, POI category `10009`) within [radiusM] of [at]; resolve with [place]. */
    suspend fun gasStations(at: GeoPoint, radiusM: Int): List<PlaceSuggestion>
}
