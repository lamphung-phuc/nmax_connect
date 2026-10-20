package com.lampp.nmaxconnect.core.weather

import com.lampp.nmaxconnect.core.protocol.ccu.CcuWeather
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenMeteoTest {
    // Shape of /v1/forecast with our current/hourly/daily lists (values made up); day 6 has no data.
    private val body = """
        {
          "latitude": 10.78, "longitude": 106.7, "timezone": "Asia/Bangkok", "utc_offset_seconds": 25200,
          "current": {"time": "2026-10-16T20:00", "interval": 900, "temperature_2m": 27.4, "weather_code": 0, "is_day": 0},
          "hourly": {
            "time": ["2026-10-16T20:00", "2026-10-16T21:00", "2026-10-16T22:00", "2026-10-16T23:00"],
            "temperature_2m": [27.4, 26.9, 26.5, 26.1],
            "weather_code": [0, 80, 2, 95],
            "precipitation_probability": [10, 45, 30, 70],
            "is_day": [0, 0, 0, 0]
          },
          "daily": {
            "time": ["d0", "d1", "d2", "d3", "d4", "d5", "d6"],
            "weather_code": [95, 63, 3, 1, 61, 45, null],
            "temperature_2m_max": [32.0, 31.0, 30.5, 31.5, 29.0, 28.0, null],
            "temperature_2m_min": [25.0, 25.5, 24.0, 24.5, 24.0, 23.0, null],
            "temperature_2m_mean": [28.0, 27.8, 27.0, 27.6, 26.0, 25.1, null],
            "precipitation_probability_max": [80, 60, 20, 10, 55, 5, null]
          }
        }
    """.trimIndent()

    private val forecast = Json { ignoreUnknownKeys = true }.decodeFromString<OpenMeteoForecast>(body)

    @Test fun buildsYConnectSlotLayout() {
        val slots = dashWeatherSlots(forecast)
        assertEquals("now, days 1-5 (6 missing), hours 1-3", listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), slots.map { it.slot })

        assertEquals(DashWeatherSlot(0, CcuWeather.SUNNY or CcuWeather.NIGHT, 27.4, 32.0, 25.0, 80), slots[0])
        assertEquals(DashWeatherSlot(1, CcuWeather.RAIN, 27.8, 31.0, 25.5, 60), slots[1])
        assertEquals(DashWeatherSlot(6, CcuWeather.SHOWERS or CcuWeather.NIGHT, 26.9, 0.0, 0.0, 45), slots[6])
        assertEquals(DashWeatherSlot(8, CcuWeather.THUNDERSTORM, 26.1, 0.0, 0.0, 70), slots[8])
    }

    @Test fun wmoCodesMapToDashIcons() {
        assertEquals(CcuWeather.SUNNY, dashWeatherIcon(0, isDay = true))
        assertEquals(CcuWeather.PARTLY_CLOUDY or CcuWeather.NIGHT, dashWeatherIcon(1, isDay = false))
        assertEquals("overcast has no night icon", CcuWeather.OVERCAST, dashWeatherIcon(3, isDay = false))
        assertEquals(CcuWeather.FOG, dashWeatherIcon(48, isDay = true))
        assertEquals(CcuWeather.RAIN_LIGHT, dashWeatherIcon(51, isDay = true))
        assertEquals(CcuWeather.RAIN_HEAVY, dashWeatherIcon(65, isDay = true))
        assertEquals(CcuWeather.SLEET_HEAVY, dashWeatherIcon(67, isDay = true))
        assertEquals(CcuWeather.SNOW, dashWeatherIcon(73, isDay = true))
        assertEquals(CcuWeather.HAIL, dashWeatherIcon(99, isDay = true))
        assertEquals(CcuWeather.NO_DATA, dashWeatherIcon(42, isDay = true))
    }
}
