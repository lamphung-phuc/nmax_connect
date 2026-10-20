package com.lampp.nmaxconnect.core.bike.ccu

import android.util.Log
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.protocol.ccu.CcuEvent
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import com.lampp.nmaxconnect.core.protocol.ccu.CcuRequestIds
import com.lampp.nmaxconnect.core.vietmap.PlaceSearchApi
import com.lampp.nmaxconnect.core.weather.OpenMeteoClient
import com.lampp.nmaxconnect.core.weather.dashWeatherSlots
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Answers the dash's weather request (cmd 3): place name (4, VietMap Reverse) then the forecast slots (5,
 * Open-Meteo). Like Y-Connect it only sends when the dash asks; a newer request cancels an older one.
 */
@Singleton
class DashWeather @Inject constructor(
    private val ccu: CcuLinkRepository,
    private val weather: OpenMeteoClient,
    private val places: PlaceSearchApi,
    private val location: LocationSource,
) {
    private val ids = CcuRequestIds()

    suspend fun run() {
        ccu.events.filterIsInstance<CcuEvent.WeatherRequest>().collectLatest { request ->
            val point = request.lat?.let { lat -> request.lng?.let { GeoPoint(lat, it) } } ?: location.current()
            if (point == null) {
                Log.w(TAG, "Weather requested but no position")
                return@collectLatest
            }
            val forecast = try {
                weather.forecast(point)
            } catch (e: IOException) {
                Log.w(TAG, "Open-Meteo failed", e)
                return@collectLatest
            }
            placeName(point)?.let { ccu.sendControl(CcuMessages.weatherLocation(ids.next(), it)) }
            for (s in dashWeatherSlots(forecast)) {
                ccu.sendControl(CcuMessages.weatherInfo(ids.next(), s.slot, s.icon, s.tempC, s.maxC, s.minC, s.popPercent))
            }
        }
    }

    /** Most local part of the VietMap address ("Phường 4,Quận 5,…" → "Phường 4"); null without the proxy. */
    private suspend fun placeName(point: GeoPoint): String? {
        if (!places.isConfigured) return null
        val address = try {
            places.reverse(point).firstOrNull()?.address
        } catch (e: IOException) {
            Log.w(TAG, "Reverse geocode failed", e)
            null
        }
        return address?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "DashWeather"
    }
}
