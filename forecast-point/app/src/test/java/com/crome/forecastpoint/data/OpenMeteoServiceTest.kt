package com.crome.forecastpoint.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenMeteoServiceTest {
    private val service = OpenMeteoService()

    @Test
    fun weatherCodes_haveStableDescriptions() {
        assertEquals("Clear sky", service.weatherDescription(0))
        assertEquals("Rain showers", service.weatherDescription(81))
        assertEquals("Thunderstorms with hail", service.weatherDescription(99))
        assertEquals("Forecast unavailable", service.weatherDescription(null))
    }

    @Test
    fun weatherIcons_preserveDayAndNight() {
        assertEquals("skc", service.weatherIcon(0, isDay = true))
        assertEquals("nskc", service.weatherIcon(0, isDay = false))
        assertEquals("rain_showers", service.weatherIcon(82, isDay = true))
        assertEquals("ntsra", service.weatherIcon(95, isDay = false))
    }
}
