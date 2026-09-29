/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Gestisce il GPS locale del ricevitore durante la verifica distanza.
 * @modified 29.09.2026 - MDS | Prima implementazione foreground con intervallo dinamico.
 */
package it.xcc.findme.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import it.xcc.findme.core.DeviceLocation
import java.time.Instant

class ReceiverLocationController(
    context: Context,
    private val deviceId: () -> String,
    private val onLocation: (DeviceLocation) -> Unit,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var activeIntervalSec: Int? = null
    private val activeProviders = mutableSetOf<String>()

    private val listener = object : LocationListener {
        /** Converte la posizione Android nel modello condiviso. */
        override fun onLocationChanged(location: Location) {
            onLocation(
                DeviceLocation(
                    deviceId = deviceId(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    recordedAt = Instant.ofEpochMilli(location.time).toString(),
                ),
            )
        }

        /** Aggiorna la disponibilità quando un provider viene disabilitato. */
        override fun onProviderDisabled(provider: String) {
            activeProviders -= provider
            onAvailabilityChanged(activeProviders.isNotEmpty())
        }

        /** Aggiorna la disponibilità quando un provider viene abilitato. */
        override fun onProviderEnabled(provider: String) {
            if (provider in TRACKED_PROVIDERS) activeProviders += provider
            onAvailabilityChanged(activeProviders.isNotEmpty())
        }
    }

    /**
     * Avvia o riallinea gli aggiornamenti alla frequenza effettiva corrente.
     */
    @SuppressLint("MissingPermission")
    fun start(intervalSec: Int) {
        if (activeIntervalSec == intervalSec && activeProviders.isNotEmpty()) return
        stop()
        activeIntervalSec = intervalSec
        TRACKED_PROVIDERS
            .filter(locationManager::isProviderEnabled)
            .forEach { provider ->
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalSec * 1_000L,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }.onSuccess {
                    activeProviders += provider
                    locationManager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                }
            }
        onAvailabilityChanged(activeProviders.isNotEmpty())
    }

    /** Arresta ogni aggiornamento e libera i listener Android. */
    fun stop() {
        locationManager.removeUpdates(listener)
        activeProviders.clear()
        activeIntervalSec = null
        onAvailabilityChanged(false)
    }

    private companion object {
        val TRACKED_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}
