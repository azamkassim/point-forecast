package com.crome.forecastpoint.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherMathTest {
    @Test
    fun feelsLike_requiresTemperature() {
        assertNull(WeatherMath.feelsLikeF(null, 70, 10))
    }

    @Test
    fun feelsLike_usesWindChillForColdBreezyWeather() {
        assertEquals(24, WeatherMath.feelsLikeF(32, 50, 10))
    }

    @Test
    fun dewPoint_rejectsInvalidHumidity() {
        assertNull(WeatherMath.dewPointF(70, 0))
        assertNull(WeatherMath.dewPointF(70, 101))
    }

    @Test
    fun conversions_matchKnownValues() {
        assertEquals(32, WeatherMath.celsiusToF(0.0))
        assertEquals(62, WeatherMath.kmhToMph(100.0))
        assertEquals(1.0, WeatherMath.mmToInches(25.4), 0.0001)
    }

    @Test
    fun degreesToCardinal_wrapsNorthAtThreeSixty() {
        assertEquals("N", WeatherMath.degreesToCardinal(0))
        assertEquals("E", WeatherMath.degreesToCardinal(90))
        assertEquals("N", WeatherMath.degreesToCardinal(360))
    }
}
