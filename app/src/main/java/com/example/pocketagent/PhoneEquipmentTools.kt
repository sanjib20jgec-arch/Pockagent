package com.example.pocketagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

private val torchState = AtomicBoolean(false)

/**
 * Controls the device camera torch/flashlight without requiring CAMERA permission on API 23+.
 * Supports modes: "on", "off", "toggle", and "sos" (3 short pulses).
 */
fun toggleFlashlight(context: Context, mode: String): String {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: return "Camera service unavailable."

    val cameraId = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: CameraAccessException) {
        return "Could not access camera hardware: ${e.message ?: e.javaClass.simpleName}"
    } catch (e: SecurityException) {
        return "Camera access denied: ${e.message ?: e.javaClass.simpleName}"
    } ?: return "No flashlight hardware available on this device."

    val normalized = mode.trim().lowercase(Locale.ROOT)
    return try {
        when (normalized) {
            "on" -> {
                cameraManager.setTorchMode(cameraId, true)
                torchState.set(true)
                "Flashlight turned ON."
            }
            "off" -> {
                cameraManager.setTorchMode(cameraId, false)
                torchState.set(false)
                "Flashlight turned OFF."
            }
            "toggle", "" -> {
                val next = !torchState.get()
                cameraManager.setTorchMode(cameraId, next)
                torchState.set(next)
                if (next) "Flashlight toggled ON." else "Flashlight toggled OFF."
            }
            "sos" -> {
                try {
                    for (pulse in 0 until 3) {
                        cameraManager.setTorchMode(cameraId, true)
                        Thread.sleep(150L)
                        cameraManager.setTorchMode(cameraId, false)
                        if (pulse < 2) {
                            Thread.sleep(150L)
                        }
                    }
                    "Flashlight SOS signal completed (3 short pulses)."
                } finally {
                    runCatching { cameraManager.setTorchMode(cameraId, false) }
                    torchState.set(false)
                }
            }
            else -> "Unsupported flashlight mode: '$mode'. Use on, off, toggle, or sos."
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        "Flashlight operation interrupted."
    } catch (e: CameraAccessException) {
        "Flashlight error: ${e.message ?: e.javaClass.simpleName}"
    } catch (e: SecurityException) {
        "Flashlight permission error: ${e.message ?: e.javaClass.simpleName}"
    } catch (e: IllegalArgumentException) {
        "Flashlight unavailable for camera '$cameraId': ${e.message ?: e.javaClass.simpleName}"
    }
}

private data class SensorSpec(
    val type: Int,
    val label: String,
    val keys: Set<String>,
    val formatter: (FloatArray) -> String,
)

private val SUPPORTED_SENSORS = listOf(
    SensorSpec(
        type = Sensor.TYPE_LIGHT,
        label = "Light",
        keys = setOf("light", "lux", "ambient"),
        formatter = { v -> String.format(Locale.US, "%.1f lx", v.getOrElse(0) { 0f }) },
    ),
    SensorSpec(
        type = Sensor.TYPE_PRESSURE,
        label = "Barometer",
        keys = setOf("pressure", "barometer", "hpa"),
        formatter = { v -> String.format(Locale.US, "%.1f hPa", v.getOrElse(0) { 0f }) },
    ),
    SensorSpec(
        type = Sensor.TYPE_ACCELEROMETER,
        label = "Accelerometer",
        keys = setOf("accelerometer", "accel", "motion"),
        formatter = { v ->
            String.format(
                Locale.US,
                "x=%.2f, y=%.2f, z=%.2f m/s^2",
                v.getOrElse(0) { 0f },
                v.getOrElse(1) { 0f },
                v.getOrElse(2) { 0f },
            )
        },
    ),
    SensorSpec(
        type = Sensor.TYPE_MAGNETIC_FIELD,
        label = "Magnetometer",
        keys = setOf("magnetic", "magnetometer", "compass"),
        formatter = { v ->
            String.format(
                Locale.US,
                "x=%.1f, y=%.1f, z=%.1f uT",
                v.getOrElse(0) { 0f },
                v.getOrElse(1) { 0f },
                v.getOrElse(2) { 0f },
            )
        },
    ),
    SensorSpec(
        type = Sensor.TYPE_PROXIMITY,
        label = "Proximity",
        keys = setOf("proximity", "prox"),
        formatter = { v -> String.format(Locale.US, "%.1f cm", v.getOrElse(0) { 0f }) },
    ),
)

/**
 * Performs a one-shot bounded (800ms) read of available device hardware sensors.
 */
fun readSensors(context: Context, mode: String): String {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ?: return "Sensor service unavailable."

    val normalized = mode.trim().lowercase(Locale.ROOT)
    val requestedSpecs = if (normalized.isBlank() || normalized == "all") {
        SUPPORTED_SENSORS
    } else {
        val matched = SUPPORTED_SENSORS.filter { spec ->
            spec.keys.any { key -> normalized.contains(key) }
        }
        matched.ifEmpty { SUPPORTED_SENSORS }
    }

    val available = mutableListOf<Pair<SensorSpec, Sensor>>()
    val absent = mutableListOf<SensorSpec>()
    for (spec in requestedSpecs) {
        val sensor = sensorManager.getDefaultSensor(spec.type)
        if (sensor != null) {
            available.add(spec to sensor)
        } else {
            absent.add(spec)
        }
    }

    val readings = ConcurrentHashMap<Int, FloatArray>()
    if (available.isNotEmpty()) {
        val latch = CountDownLatch(available.size)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val type = event.sensor.type
                if (readings.putIfAbsent(type, event.values.copyOf()) == null) {
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        try {
            for ((_, sensor) in available) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            }
            latch.await(800L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            sensorManager.unregisterListener(listener)
        }
    }

    val lines = mutableListOf<String>()
    for ((spec, _) in available) {
        val values = readings[spec.type]
        if (values != null) {
            lines.add("${spec.label}: ${spec.formatter(values)}")
        } else {
            lines.add("${spec.label}: no reading within 800ms")
        }
    }
    for (spec in absent) {
        lines.add("${spec.label}: not available on this device")
    }
    return lines.joinToString("\n")
}

/**
 * Reads the last known offline GPS location and computes estimated local sunrise/sunset times.
 */
fun getOfflineLocation(context: Context): String {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        return "Location permission not granted. Grant Location permission in Android Settings to use offline GPS."
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return "Location service unavailable."

    val location = readLastKnownGpsLocation(locationManager)
        ?: return "No cached offline GPS fix available. Ensure GPS is enabled and has a recent fix."

    val now = ZonedDateTime.now()
    val dayOfYear = now.dayOfYear
    val utcOffsetHours = now.offset.totalSeconds / 3600.0
    val solarTimes = computeSolarTimes(
        latDeg = location.latitude,
        lonDeg = location.longitude,
        dayOfYear = dayOfYear,
        utcOffsetHours = utcOffsetHours,
    )

    val altitude = if (location.hasAltitude()) {
        String.format(Locale.US, "%.1f m", location.altitude)
    } else {
        "unavailable"
    }
    val speed = if (location.hasSpeed()) {
        String.format(Locale.US, "%.2f m/s", location.speed)
    } else {
        "unavailable"
    }

    return buildString {
        appendLine(
            String.format(
                Locale.US,
                "Offline GPS: Lat %.6f, Lon %.6f",
                location.latitude,
                location.longitude,
            )
        )
        appendLine("Altitude: $altitude, Speed: $speed")
        append(solarTimes)
    }
}

@SuppressLint("MissingPermission")
private fun readLastKnownGpsLocation(locationManager: LocationManager): Location? {
    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
}

/**
 * Computes estimated local sunrise and sunset times (HH:mm) from latitude, longitude,
 * day of year (1..366), and UTC offset in hours using NOAA solar equations.
 */
fun computeSolarTimes(
    latDeg: Double,
    lonDeg: Double,
    dayOfYear: Int,
    utcOffsetHours: Double,
): String {
    val clampedDay = dayOfYear.coerceIn(1, 366)
    val clampedLat = latDeg.coerceIn(-90.0, 90.0)
    val gamma = 2.0 * Math.PI / 365.0 * (clampedDay - 1)

    val eqTime = 229.18 * (
        0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2.0 * gamma) -
            0.040849 * sin(2.0 * gamma)
        )

    val decl = 0.006918 -
        0.399912 * cos(gamma) +
        0.070257 * sin(gamma) -
        0.006758 * cos(2.0 * gamma) +
        0.000907 * sin(2.0 * gamma) -
        0.002697 * cos(3.0 * gamma) +
        0.00148 * sin(3.0 * gamma)

    val latRad = Math.toRadians(clampedLat)
    val zenithRad = Math.toRadians(90.833)
    val cosLat = cos(latRad)

    // Guard exact poles where cos(latRad) approaches 0 to avoid division by zero / NaN.
    val cosHa = if (kotlin.math.abs(cosLat) < 1e-9) {
        val sameHemisphere = (clampedLat >= 0.0 && decl >= 0.0) || (clampedLat < 0.0 && decl < 0.0)
        if (sameHemisphere) -2.0 else 2.0
    } else {
        (cos(zenithRad) / (cosLat * cos(decl))) - tan(latRad) * tan(decl)
    }

    if (cosHa > 1.0) {
        return "Polar night (sun remains below horizon all day)"
    }
    if (cosHa < -1.0) {
        return "Midnight sun (sun remains above horizon all day)"
    }

    val haDeg = Math.toDegrees(acos(cosHa))
    val sunriseUtcMin = 720.0 - 4.0 * (lonDeg + haDeg) - eqTime
    val sunsetUtcMin = 720.0 - 4.0 * (lonDeg - haDeg) - eqTime

    val sunriseLocalMin = normalizeMinutes(sunriseUtcMin + utcOffsetHours * 60.0)
    val sunsetLocalMin = normalizeMinutes(sunsetUtcMin + utcOffsetHours * 60.0)

    return "Sunrise: ${formatMinutes(sunriseLocalMin)}, Sunset: ${formatMinutes(sunsetLocalMin)}"
}

private fun normalizeMinutes(minutes: Double): Int {
    val rounded = minutes.roundToInt()
    return ((rounded % 1440) + 1440) % 1440
}

private fun formatMinutes(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return String.format(Locale.US, "%02d:%02d", hours, mins)
}
