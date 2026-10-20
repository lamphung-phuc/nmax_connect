package com.lampp.nmaxconnect.core.weather

import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.protocol.ccu.CcuWeather
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The subset of Open-Meteo's /v1/forecast we request. Array entries can be null for missing model data. */
@Serializable
data class OpenMeteoForecast(val current: Current, val hourly: Hourly, val daily: Daily) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("is_day") val isDay: Int = 1,
    )

    @Serializable
    data class Hourly(
        @SerialName("temperature_2m") val temperature: List<Double?>,
        @SerialName("weather_code") val weatherCode: List<Int?>,
        @SerialName("precipitation_probability") val pop: List<Int?> = emptyList(),
        @SerialName("is_day") val isDay: List<Int?> = emptyList(),
    )

    @Serializable
    data class Daily(
        @SerialName("weather_code") val weatherCode: List<Int?>,
        @SerialName("temperature_2m_max") val max: List<Double?>,
        @SerialName("temperature_2m_min") val min: List<Double?>,
        @SerialName("temperature_2m_mean") val mean: List<Double?> = emptyList(),
        @SerialName("precipitation_probability_max") val pop: List<Int?> = emptyList(),
    )
}

/** Open-Meteo forecast (no key; free for non-commercial use). Called directly: there is no secret to hide. */
class OpenMeteoClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun forecast(point: GeoPoint): OpenMeteoForecast = withContext(ioDispatcher) {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", point.lat.toString())
            .addQueryParameter("longitude", point.lng.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("hourly", "temperature_2m,weather_code,precipitation_probability,is_day")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,temperature_2m_mean,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "7")
            .addQueryParameter("forecast_hours", "4") // this hour + the next 3
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Open-Meteo HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Open-Meteo: empty body")
            try {
                json.decodeFromString<OpenMeteoForecast>(body)
            } catch (e: IllegalArgumentException) { // SerializationException extends it
                throw IOException("Open-Meteo: unexpected response (${e.message})", e)
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    }
}

/** One cmd-5 slot, ready for CcuMessages.weatherInfo. */
data class DashWeatherSlot(val slot: Int, val icon: Int, val tempC: Double, val maxC: Double, val minC: Double, val popPercent: Int)

/**
 * Y-Connect's slot layout: now = current conditions with today's max/min/rain chance; days +1..+6 = each
 * day's icon, mean temperature, max, min, rain chance; hours +1..+3 = icon, temperature, rain chance (max/min 0).
 * Slots with missing data are left out.
 */
fun dashWeatherSlots(f: OpenMeteoForecast): List<DashWeatherSlot> = buildList {
    val d = f.daily
    add(
        DashWeatherSlot(
            CcuWeather.SLOT_NOW,
            dashWeatherIcon(f.current.weatherCode, f.current.isDay == 1),
            f.current.temperature,
            d.max.getOrNull(0) ?: f.current.temperature,
            d.min.getOrNull(0) ?: f.current.temperature,
            d.pop.getOrNull(0) ?: 0,
        ),
    )
    for (day in 1..6) {
        val code = d.weatherCode.getOrNull(day) ?: continue
        val max = d.max.getOrNull(day) ?: continue
        val min = d.min.getOrNull(day) ?: continue
        add(DashWeatherSlot(CcuWeather.daySlot(day), dashWeatherIcon(code, isDay = true), d.mean.getOrNull(day) ?: (max + min) / 2, max, min, d.pop.getOrNull(day) ?: 0))
    }
    val h = f.hourly
    for (hour in 1..3) {
        val code = h.weatherCode.getOrNull(hour) ?: continue
        val temp = h.temperature.getOrNull(hour) ?: continue
        add(DashWeatherSlot(CcuWeather.hourSlot(hour), dashWeatherIcon(code, h.isDay.getOrNull(hour) != 0), temp, 0.0, 0.0, h.pop.getOrNull(hour) ?: 0))
    }
}

/** WMO weather code (Open-Meteo) → dash icon, mirroring Y-Connect's OpenWeather table (createWeather). */
fun dashWeatherIcon(wmo: Int, isDay: Boolean): Int {
    /** Only clear, partly cloudy, cloudy, showers and scattered storms have night icons on the dash. */
    fun dayOrNight(icon: Int) = if (isDay) icon else icon or CcuWeather.NIGHT
    return when (wmo) {
        0 -> dayOrNight(CcuWeather.SUNNY)
        1 -> dayOrNight(CcuWeather.PARTLY_CLOUDY)
        2 -> dayOrNight(CcuWeather.CLOUDY_PARTIAL)
        3 -> CcuWeather.OVERCAST
        45, 48 -> CcuWeather.FOG
        51, 61 -> CcuWeather.RAIN_LIGHT
        53, 63 -> CcuWeather.RAIN
        55, 65, 82 -> CcuWeather.RAIN_HEAVY
        56, 66 -> CcuWeather.SLEET_LIGHT
        57 -> CcuWeather.SLEET
        67 -> CcuWeather.SLEET_HEAVY
        71, 77, 85 -> CcuWeather.SNOW_LIGHT
        73 -> CcuWeather.SNOW
        75, 86 -> CcuWeather.SNOW_HEAVY
        80, 81 -> dayOrNight(CcuWeather.SHOWERS)
        95 -> CcuWeather.THUNDERSTORM
        96, 99 -> CcuWeather.HAIL
        else -> CcuWeather.NO_DATA
    }
}
