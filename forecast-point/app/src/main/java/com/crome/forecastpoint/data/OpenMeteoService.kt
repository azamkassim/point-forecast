package com.crome.forecastpoint.data

import com.crome.forecastpoint.util.WeatherMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Open-Meteo free APIs (no key) for fields NWS digitalJSON often lacks hourly:
 * UV index, surface pressure, visibility fallback, and US AQI.
 *
 * Docs: https://open-meteo.com/ — non-commercial free tier.
 */
class OpenMeteoService(
    private val client: OkHttpClient = defaultClient(),
) {
    /** Worldwide forecast used only when NWS reports that a point is out of coverage. */
    suspend fun fetchForecast(
        latitude: Double,
        longitude: Double,
        preferredName: String? = null,
        includeAirQuality: Boolean = true,
    ): WeatherSnapshot = withContext(Dispatchers.IO) {
        val lat = String.format(Locale.US, "%.4f", latitude)
        val lon = String.format(Locale.US, "%.4f", longitude)
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "is_day,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m," +
                "visibility,dew_point_2m" +
                "&hourly=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "precipitation_probability,precipitation,weather_code,pressure_msl," +
                "cloud_cover,visibility,wind_speed_10m,wind_direction_10m,wind_gusts_10m," +
                "dew_point_2m,uv_index,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,sunrise,sunset" +
                "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
                "&timezone=auto&forecast_days=7"
        val root = JSONObject(getString(url))
        val snapshot = parseForecast(root, latitude, longitude, preferredName)
        if (!includeAirQuality) return@withContext snapshot
        val air = runCatching { fetchAirQuality(latitude, longitude) }
            .getOrDefault(HourlyExtras.EMPTY)
        snapshot.copy(
            hourly = snapshot.hourly.map { row ->
                val epoch = row.epochSec ?: return@map row
                val hour = (epoch / 3600L) * 3600L
                row.copy(usAqi = air.usAqi[hour], pm25 = air.pm25[hour])
            },
        )
    }

    internal fun parseForecast(
        root: JSONObject,
        latitude: Double,
        longitude: Double,
        preferredName: String?,
    ): WeatherSnapshot {
        val timeZone = TimeZone.getTimeZone(root.optString("timezone").ifBlank { "UTC" })
        val elevationFt = root.optDouble("elevation", Double.NaN)
            .takeIf { !it.isNaN() }
            ?.let { (it * 3.28084).roundToInt() }
        val currentJson = root.optJSONObject("current") ?: JSONObject()
        val currentEpoch = parseLocalTime(currentJson.optString("time"), timeZone)
        val currentCode = nullableInt(currentJson, "weather_code")
        val currentIsDay = nullableInt(currentJson, "is_day") == 1
        val currentTemp = nullableInt(currentJson, "temperature_2m")
        val currentHumidity = nullableInt(currentJson, "relative_humidity_2m")
        val pressureMb = nullableDouble(currentJson, "pressure_msl")
        val current = CurrentConditions(
            temperatureF = currentTemp,
            weather = weatherDescription(currentCode),
            iconCode = weatherIcon(currentCode, currentIsDay),
            feelsLikeF = nullableInt(currentJson, "apparent_temperature"),
            humidityPct = currentHumidity,
            windDirection = WeatherMath.degreesToCardinal(nullableInt(currentJson, "wind_direction_10m")),
            windSpeedMph = nullableInt(currentJson, "wind_speed_10m"),
            dewPointF = nullableInt(currentJson, "dew_point_2m")
                ?: WeatherMath.dewPointF(currentTemp, currentHumidity),
            visibilityMi = nullableDouble(currentJson, "visibility")?.let {
                String.format(Locale.US, "%.1f", it / FEET_PER_MILE)
            },
            barometerInHg = pressureMb?.let {
                String.format(Locale.US, "%.2f", WeatherMath.hPaToInHg(it))
            },
            barometerMb = pressureMb?.let { String.format(Locale.US, "%.1f", it) },
            stationName = "Open-Meteo global forecast",
            observedAt = currentEpoch?.let { formatEpoch(it, timeZone, "MMM d, h:mm a") },
            elevationFt = elevationFt,
        )

        val hourlyJson = root.optJSONObject("hourly") ?: JSONObject()
        val hourlyTimes = hourlyJson.optJSONArray("time")
        val hourly = buildList {
            for (i in 0 until (hourlyTimes?.length() ?: 0)) {
                val epoch = parseLocalTime(hourlyTimes?.optString(i).orEmpty(), timeZone) ?: continue
                val code = arrayInt(hourlyJson, "weather_code", i)
                val isDay = arrayInt(hourlyJson, "is_day", i) == 1
                val temp = arrayInt(hourlyJson, "temperature_2m", i)
                val humidity = arrayInt(hourlyJson, "relative_humidity_2m", i)
                val precip = arrayDouble(hourlyJson, "precipitation", i)
                add(
                    HourlyRow(
                        periodLabel = formatEpoch(epoch, timeZone, "EEE"),
                        timeLabel = formatEpoch(epoch, timeZone, "h a").lowercase(Locale.US),
                        temperatureF = temp,
                        feelsLikeF = arrayInt(hourlyJson, "apparent_temperature", i),
                        dewPointF = arrayInt(hourlyJson, "dew_point_2m", i)
                            ?: WeatherMath.dewPointF(temp, humidity),
                        popPct = arrayInt(hourlyJson, "precipitation_probability", i),
                        precipIn = precip?.takeIf { it > 0.0 }
                            ?.let { String.format(Locale.US, "%.2f in", it) },
                        cloudCoverPct = arrayInt(hourlyJson, "cloud_cover", i),
                        humidityPct = humidity,
                        windSpeedMph = arrayInt(hourlyJson, "wind_speed_10m", i),
                        windGustMph = arrayInt(hourlyJson, "wind_gusts_10m", i),
                        windDirection = WeatherMath.degreesToCardinal(
                            arrayInt(hourlyJson, "wind_direction_10m", i),
                        ),
                        weather = weatherDescription(code),
                        iconCode = weatherIcon(code, isDay),
                        epochSec = epoch,
                        visibilityMi = arrayDouble(hourlyJson, "visibility", i)
                            ?.div(FEET_PER_MILE),
                        pressureMb = arrayDouble(hourlyJson, "pressure_msl", i),
                        uvIndex = arrayDouble(hourlyJson, "uv_index", i),
                    ),
                )
            }
        }

        val dailyJson = root.optJSONObject("daily") ?: JSONObject()
        val dailyTimes = dailyJson.optJSONArray("time")
        val days = buildList {
            for (i in 0 until (dailyTimes?.length() ?: 0)) {
                val epoch = parseLocalDate(dailyTimes?.optString(i).orEmpty(), timeZone) ?: continue
                val code = arrayInt(dailyJson, "weather_code", i)
                add(
                    DayForecast(
                        dayName = formatEpoch(epoch, timeZone, "EEE"),
                        dateLabel = formatEpoch(epoch, timeZone, "EEEE, MMM d, yyyy"),
                        highF = arrayInt(dailyJson, "temperature_2m_max", i),
                        lowF = arrayInt(dailyJson, "temperature_2m_min", i),
                        popPct = arrayInt(dailyJson, "precipitation_probability_max", i),
                        summary = weatherDescription(code),
                        detailed = "Open-Meteo daily forecast",
                        iconCode = weatherIcon(code, true),
                        sunrise = formatDailyClock(dailyJson, "sunrise", i, timeZone),
                        sunset = formatDailyClock(dailyJson, "sunset", i, timeZone),
                    ),
                )
            }
        }
        val periods = days.map { day ->
            ForecastPeriod(
                name = day.dayName,
                startTimeIso = null,
                isDaytime = true,
                temperatureF = day.highF,
                tempLabel = "High",
                popPct = day.popPct,
                weather = day.summary,
                detailedForecast = day.detailed,
                iconCode = day.iconCode,
            )
        }

        return WeatherSnapshot(
            locationName = preferredName?.takeIf { it.isNotBlank() } ?: "Selected location",
            latitude = latitude,
            longitude = longitude,
            elevationFt = elevationFt,
            updatedAtEpochMs = System.currentTimeMillis(),
            observationTimeLabel = current.observedAt,
            current = current,
            periods = periods,
            days = days,
            hourly = hourly,
            sunrise = days.firstOrNull()?.sunrise,
            sunset = days.firstOrNull()?.sunset,
            hazards = emptyList(),
            tideInfo = null,
            timeZoneId = timeZone.id,
            forecastSource = "Open-Meteo",
        )
    }

    data class HourlyExtras(
        val visibilityMi: Map<Long, Double> = emptyMap(),
        val pressureMb: Map<Long, Double> = emptyMap(),
        val uvIndex: Map<Long, Double> = emptyMap(),
        val usAqi: Map<Long, Int> = emptyMap(),
        val pm25: Map<Long, Double> = emptyMap(),
    ) {
        companion object {
            val EMPTY = HourlyExtras()
        }
    }

    suspend fun fetchExtras(
        latitude: Double,
        longitude: Double,
        includeWeather: Boolean = true,
        includeAirQuality: Boolean = true,
    ): HourlyExtras =
        withContext(Dispatchers.IO) {
            if (!includeWeather && !includeAirQuality) return@withContext HourlyExtras.EMPTY
            coroutineScope {
                val weather = async {
                    if (!includeWeather) {
                        HourlyExtras.EMPTY
                    } else {
                        runCatching { fetchWeatherHourly(latitude, longitude) }
                            .getOrDefault(HourlyExtras.EMPTY)
                    }
                }
                val air = async {
                    if (!includeAirQuality) {
                        HourlyExtras.EMPTY
                    } else {
                        runCatching { fetchAirQuality(latitude, longitude) }
                            .getOrDefault(HourlyExtras.EMPTY)
                    }
                }
                val w = weather.await()
                val a = air.await()
                HourlyExtras(
                    visibilityMi = w.visibilityMi,
                    pressureMb = w.pressureMb,
                    uvIndex = w.uvIndex,
                    usAqi = a.usAqi,
                    pm25 = a.pm25,
                )
            }
        }

    private fun fetchWeatherHourly(latitude: Double, longitude: Double): HourlyExtras {
        val lat = String.format(Locale.US, "%.4f", latitude)
        val lon = String.format(Locale.US, "%.4f", longitude)
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=visibility,surface_pressure,uv_index" +
                "&timezone=UTC&forecast_days=7"
        val root = JSONObject(getString(url))
        val hourly = root.optJSONObject("hourly") ?: return HourlyExtras.EMPTY
        val times = hourly.optJSONArray("time") ?: return HourlyExtras.EMPTY
        val vis = hourly.optJSONArray("visibility")
        val pres = hourly.optJSONArray("surface_pressure")
        val uv = hourly.optJSONArray("uv_index")
        val visMap = mutableMapOf<Long, Double>()
        val presMap = mutableMapOf<Long, Double>()
        val uvMap = mutableMapOf<Long, Double>()
        for (i in 0 until times.length()) {
            val epoch = parseUtcHour(times.optString(i)) ?: continue
            vis?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }?.let {
                visMap[epoch] = WeatherMath.metersToMiles(it)
            }
            pres?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }?.let {
                presMap[epoch] = it
            }
            uv?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }?.let {
                uvMap[epoch] = it
            }
        }
        return HourlyExtras(visibilityMi = visMap, pressureMb = presMap, uvIndex = uvMap)
    }

    private fun fetchAirQuality(latitude: Double, longitude: Double): HourlyExtras {
        val lat = String.format(Locale.US, "%.4f", latitude)
        val lon = String.format(Locale.US, "%.4f", longitude)
        val url =
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                "&hourly=us_aqi,pm2_5&timezone=UTC&forecast_days=5"
        val root = JSONObject(getString(url))
        val hourly = root.optJSONObject("hourly") ?: return HourlyExtras.EMPTY
        val times = hourly.optJSONArray("time") ?: return HourlyExtras.EMPTY
        val aqi = hourly.optJSONArray("us_aqi")
        val pm = hourly.optJSONArray("pm2_5")
        val aqiMap = mutableMapOf<Long, Int>()
        val pmMap = mutableMapOf<Long, Double>()
        for (i in 0 until times.length()) {
            val epoch = parseUtcHour(times.optString(i)) ?: continue
            if (aqi != null && !aqi.isNull(i)) {
                val v = aqi.optDouble(i, Double.NaN)
                if (!v.isNaN()) aqiMap[epoch] = v.toInt()
            }
            if (pm != null && !pm.isNull(i)) {
                val v = pm.optDouble(i, Double.NaN)
                if (!v.isNaN()) pmMap[epoch] = v
            }
        }
        return HourlyExtras(usAqi = aqiMap, pm25 = pmMap)
    }

    private fun nullableDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
    }

    private fun nullableInt(obj: JSONObject, key: String): Int? =
        nullableDouble(obj, key)?.roundToInt()

    private fun arrayDouble(obj: JSONObject, key: String, index: Int): Double? {
        val arr = obj.optJSONArray(key) ?: return null
        if (index >= arr.length() || arr.isNull(index)) return null
        return arr.optDouble(index, Double.NaN).takeIf { !it.isNaN() }
    }

    private fun arrayInt(obj: JSONObject, key: String, index: Int): Int? =
        arrayDouble(obj, key, index)?.roundToInt()

    private fun parseLocalTime(value: String, timeZone: TimeZone): Long? {
        if (value.isBlank()) return null
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
            this.timeZone = timeZone
        }
        return runCatching { format.parse(value)?.time?.div(1000L) }.getOrNull()
    }

    private fun parseLocalDate(value: String, timeZone: TimeZone): Long? {
        if (value.isBlank()) return null
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }
        return runCatching { format.parse(value)?.time?.div(1000L) }.getOrNull()
    }

    private fun formatEpoch(epochSec: Long, timeZone: TimeZone, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = timeZone }
            .format(java.util.Date(epochSec * 1000L))

    private fun formatDailyClock(
        obj: JSONObject,
        key: String,
        index: Int,
        timeZone: TimeZone,
    ): String? {
        val value = obj.optJSONArray(key)?.optString(index).orEmpty()
        val epoch = parseLocalTime(value, timeZone) ?: return null
        return formatEpoch(epoch, timeZone, "h:mm a")
    }

    internal fun weatherDescription(code: Int?): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "Forecast unavailable"
    }

    internal fun weatherIcon(code: Int?, isDay: Boolean): String {
        val base = when (code) {
            0 -> "skc"
            1 -> "few"
            2 -> "sct"
            3 -> "ovc"
            45, 48 -> "fog"
            51, 53, 55, 61, 63, 65 -> "rain"
            56, 57, 66, 67 -> "fzra"
            71, 73, 75, 77, 85, 86 -> "snow"
            80, 81, 82 -> "rain_showers"
            95, 96, 99 -> "tsra"
            else -> "few"
        }
        return if (isDay) base else "n$base"
    }

    private fun parseUtcHour(isoLocal: String): Long? {
        if (isoLocal.isBlank()) return null
        // "2026-08-14T15:00" as UTC (we requested timezone=UTC)
        val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val ms = runCatching { parse.parse(isoLocal)?.time }.getOrNull() ?: return null
        return (ms / 1000L / 3600L) * 3600L
    }

    private fun getString(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty body")
        }
    }

    companion object {
        private const val FEET_PER_MILE = 5280.0
        private const val USER_AGENT =
            "PointForecast/1.1.3 (Android; open-source; https://github.com/crome1394/point-forecast)"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
    }
}
