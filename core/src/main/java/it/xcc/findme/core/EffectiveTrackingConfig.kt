package it.xcc.findme.core

import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class EffectiveTrackingConfig(
    val locationIntervalSec: Int,
    val historyIntervalSec: Int,
    val heartbeatIntervalSec: Int,
    val onlyMovement: Boolean,
    val liveTracking: Boolean,
    val liveHistory: Boolean,
)

object TrackingConfigResolver {
    val defaults = ReceiverTrackingSettings(receiverId = "")

    fun resolve(
        settings: ReceiverTrackingSettings,
        relationship: ReceiverTransmitter?,
        now: Instant = Instant.now(),
    ): EffectiveTrackingConfig {
        val live = relationship?.liveTrackingUntil
            ?.let { runCatching { Instant.parse(it).isAfter(now) }.getOrDefault(false) }
            ?: false
        val liveHistory = live && relationship?.liveHistory == true
        val locationInterval = if (live) {
            settings.onlineLocationIntervalSec
        } else {
            settings.offlineLocationIntervalSec
        }
        val historyBase = if (liveHistory) {
            settings.onlineLocationIntervalSec
        } else {
            settings.offlineLocationIntervalSec
        }
        return EffectiveTrackingConfig(
            locationIntervalSec = locationInterval,
            historyIntervalSec = historyBase * settings.historyMultiplier,
            heartbeatIntervalSec = settings.heartbeatIntervalSec,
            onlyMovement = settings.onlyMovement,
            liveTracking = live,
            liveHistory = liveHistory,
        )
    }

    fun movementThresholdMeters(previousAccuracy: Float?, currentAccuracy: Float?): Double {
        val accuracies = listOfNotNull(previousAccuracy, currentAccuracy)
        val averageAccuracy = if (accuracies.isEmpty()) {
            10.0
        } else {
            accuracies.average()
        }
        return max(10.0, min(50.0, averageAccuracy))
    }

    fun distanceMeters(from: DeviceLocation, to: DeviceLocation): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun shouldPersistHistory(
        previous: DeviceLocation?,
        current: DeviceLocation,
        lastSavedAtMillis: Long?,
        nowMillis: Long,
        config: EffectiveTrackingConfig,
    ): Boolean {
        if (lastSavedAtMillis != null &&
            nowMillis - lastSavedAtMillis < config.historyIntervalSec * 1_000L
        ) {
            return false
        }
        if (!config.onlyMovement || previous == null) return true
        return distanceMeters(previous, current) >=
            movementThresholdMeters(previous.accuracy, current.accuracy)
    }
}

object MediaConnectionPolicy {
    fun shouldConnect(cameraStreaming: Boolean, microphoneStreaming: Boolean): Boolean =
        cameraStreaming || microphoneStreaming
}

object RouteSampler {
    fun <T> sample(points: List<T>, maxPoints: Int): List<T> {
        val safeMaximum = maxPoints.coerceAtLeast(2)
        if (points.size <= safeMaximum) return points
        val stride = kotlin.math.ceil(points.size.toDouble() / safeMaximum).toInt()
        val sampled = points.filterIndexed { index, _ -> index % stride == 0 }.toMutableList()
        if (sampled.lastOrNull() != points.last()) sampled += points.last()
        return if (sampled.size <= safeMaximum) sampled else {
            sampled.take(safeMaximum - 1) + points.last()
        }
    }
}

data class GeofenceTransition(
    val isOutside: Boolean,
    val shouldNotify: Boolean,
)

object GeofencePolicy {
    val allowedRadiiM = setOf(50, 100, 250, 500, 1000)

    fun evaluate(
        wasOutside: Boolean,
        distanceM: Double,
        radiusM: Int,
    ): GeofenceTransition {
        require(radiusM in allowedRadiiM) { "Unsupported geofence radius" }
        val outside = distanceM > radiusM
        return GeofenceTransition(
            isOutside = outside,
            shouldNotify = outside && !wasOutside,
        )
    }
}
