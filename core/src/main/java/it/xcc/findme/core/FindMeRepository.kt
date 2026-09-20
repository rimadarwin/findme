@file:OptIn(
    io.github.jan.supabase.annotations.SupabaseExperimental::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package it.xcc.findme.core

import android.os.SystemClock
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.selectAsFlow
import io.ktor.client.call.body
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class FindMeRepository(
    private val client: SupabaseClient = processClient,
) {
    val isAuthenticated: Boolean get() = client.auth.currentUserOrNull() != null
    val currentUserId: String? get() = client.auth.currentUserOrNull()?.id

    suspend fun ensureAuthenticated(forceRefresh: Boolean = false) {
        processAuthenticationMutex.withLock {
            // elapsedRealtime includes deep sleep; nanoTime can make an expired JWT look recent.
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val refreshDue = ConnectionRecoveryPolicy.isSessionRefreshDue(
                lastRefreshElapsedMs = lastSessionRefreshAtElapsedMs,
                nowElapsedMs = nowElapsedMs,
            )
            if (!forceRefresh && !refreshDue) return@withLock

            withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                client.auth.awaitInitialization()
                if (isAuthenticated) {
                    client.auth.refreshCurrentSession()
                } else {
                    client.auth.signInAnonymously()
                }
            }
            lastSessionRefreshAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    suspend fun shutdownRealtime() {
        runCatching { client.realtime.removeAllChannels() }
        client.realtime.disconnect()
    }

    suspend fun registerDevice(id: String, name: String, role: DeviceRole) {
        ensureAuthenticated()
        val ownerId = requireNotNull(currentUserId) { "Utente non autenticato" }
        client.from("devices").upsert(Device(id, ownerId, name, role))
    }

    suspend fun registerReceiver(id: String, name: String) {
        ensureAuthenticated()
        val ownerId = requireNotNull(currentUserId) { "Utente non autenticato" }
        client.from("receivers").upsert(ReceiverRegistration(id, ownerId, name))
    }

    fun receiverProfile(deviceId: String): Flow<ReceiverProfile?> =
        client.from("receivers")
            .selectAsFlow(ReceiverProfile::deviceId)
            .map { rows -> rows.firstOrNull { it.deviceId == deviceId } }

    fun receiverTrackingSettings(receiverId: String): Flow<ReceiverTrackingSettings?> =
        client.from("receivers")
            .selectAsFlow(ReceiverTrackingSettings::receiverId)
            .map { rows -> rows.firstOrNull { it.receiverId == receiverId } }

    suspend fun updateReceiverTrackingSettings(
        receiverId: String,
        settings: TrackingSettingsUpdate,
    ) {
        ensureAuthenticated()
        client.from("receivers").update(settings) {
            filter { eq("device_id", receiverId) }
        }
    }

    suspend fun pairWithReceiver(
        transmitterDeviceId: String,
        pairingCode: String,
    ): PairDeviceResponse {
        ensureAuthenticated()
        return client.functions.invoke(
            function = "pair-device",
            body = PairDeviceRequest(transmitterDeviceId, pairingCode.trim().uppercase()),
        ).body()
    }

    suspend fun receiverAccess(
        transmitterDeviceId: String,
        answer: String? = null,
    ): ReceiverAccessResponse {
        ensureAuthenticated()
        return client.functions.invoke(
            function = "receiver-access",
            body = ReceiverAccessRequest(transmitterDeviceId, answer),
        ).body()
    }

    fun monitoredDevices(): Flow<List<MonitoredDevice>> {
        return client.from("receiver_transmitters")
            .selectAsFlow(ReceiverTransmitter::transmitterId)
            .flatMapLatest { relationships ->
                val relationshipByDevice = relationships.associateBy { it.transmitterId }
                val devices = client.from("devices").selectAsFlow(Device::id)
                val statuses = client.from("device_status").selectAsFlow(DeviceStatus::deviceId)
                val locations = client.from("device_locations").selectAsFlow(DeviceLocation::deviceId)
                combine(devices, statuses, locations) { deviceRows, statusRows, locationRows ->
                    val statusByDevice = statusRows.associateBy(DeviceStatus::deviceId)
                    val locationByDevice = locationRows.associateBy(DeviceLocation::deviceId)
                    deviceRows.mapNotNull {
                        val relationship = relationshipByDevice[it.id] ?: return@mapNotNull null
                        if (it.role != DeviceRole.TRANSMITTER) return@mapNotNull null
                            MonitoredDevice(
                                device = it,
                                status = statusByDevice[it.id],
                                location = locationByDevice[it.id],
                                alias = relationship.alias?.trim()?.takeIf(String::isNotEmpty),
                                relationship = relationship,
                            )
                    }
                }
            }
    }

    suspend fun updateTransmitterAlias(
        receiverId: String,
        transmitterId: String,
        alias: String,
    ) {
        ensureAuthenticated()
        val normalized = alias.trim().takeIf { it.isNotEmpty() }
        client.from("receiver_transmitters").update(
            { set("alias", normalized) },
        ) {
            filter {
                eq("receiver_id", receiverId)
                eq("transmitter_id", transmitterId)
            }
        }
    }

    fun transmitterTrackingState(transmitterId: String): Flow<TrackingRuntimeState?> =
        client.from("receiver_transmitters")
            .selectAsFlow(ReceiverTransmitter::transmitterId)
            .map { rows -> rows.firstOrNull { it.transmitterId == transmitterId } }
            .flatMapLatest { relationship ->
                if (relationship == null) {
                    flowOf(null)
                } else {
                    receiverTrackingSettings(relationship.receiverId).map { settings ->
                        settings?.let { TrackingRuntimeState(it, relationship) }
                    }
                }
            }

    suspend fun setLiveTracking(
        receiverId: String,
        transmitterId: String,
        until: Instant?,
        liveHistory: Boolean,
    ) {
        ensureAuthenticated()
        client.from("receiver_transmitters").update(
            {
                set("live_tracking_until", until?.toString())
                set("live_history", liveHistory && until != null)
            },
        ) {
            filter {
                eq("receiver_id", receiverId)
                eq("transmitter_id", transmitterId)
            }
        }
    }

    suspend fun setGeofence(
        receiverId: String,
        transmitterId: String,
        center: DeviceLocation?,
        radiusM: Int?,
    ) {
        ensureAuthenticated()
        client.from("receiver_transmitters").update(
            {
                set("geofence_enabled", center != null && radiusM != null)
                set("geofence_center_latitude", center?.latitude)
                set("geofence_center_longitude", center?.longitude)
                set("geofence_radius_m", radiusM)
                set("geofence_is_outside", false)
                set("geofence_updated_at", Instant.now().toString())
            },
        ) {
            filter {
                eq("receiver_id", receiverId)
                eq("transmitter_id", transmitterId)
            }
        }
    }

    suspend fun registerReceiverPushToken(receiverId: String, token: String) {
        ensureAuthenticated()
        client.from("receiver_push_tokens").upsert(
            ReceiverPushToken(
                token = token,
                receiverId = receiverId,
                updatedAt = Instant.now().toString(),
            ),
        )
    }

    suspend fun checkGeofence(location: DeviceLocation) {
        ensureAuthenticated()
        client.functions.invoke(
            function = "geofence-alert",
            body = GeofenceCheckRequest(
                deviceId = location.deviceId,
                latitude = location.latitude,
                longitude = location.longitude,
            ),
        )
    }

    fun commands(
        deviceId: String,
        pollingIntervalSec: () -> Int = {
            ConnectionRecoveryPolicy.DEFAULT_COMMAND_POLL_INTERVAL_SEC
        },
    ): Flow<List<DeviceCommand>> = channelFlow {
        val channel = client.channel("commands-$deviceId")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "device_commands"
            filter("device_id", FilterOperator.EQ, deviceId)
        }
        val fetchMutex = Mutex()

        suspend fun publishPendingCommands(source: String) {
            fetchMutex.withLock {
                val pending = fetchPendingCommands(deviceId)
                Log.d(TAG, "Command check source=$source pending=${pending.size}")
                send(pending)
            }
        }

        // REST polling must run before Realtime: a stalled websocket subscription must never
        // prevent already persisted commands from being consumed.
        publishPendingCommands("initial")
        val realtimeJob = launch {
            runCatching {
                withTimeout(ConnectionRecoveryPolicy.REQUEST_TIMEOUT_MS) {
                    channel.subscribe()
                }
                inserts.collect {
                    publishPendingCommands("realtime")
                }
            }.onFailure {
                Log.e(TAG, "Command Realtime listener unavailable; REST polling remains active", it)
            }
        }
        try {
            while (isActive) {
                delay(
                    ConnectionRecoveryPolicy.commandPollIntervalSec(pollingIntervalSec()) *
                        1_000L,
                )
                publishPendingCommands("poll")
            }
        } finally {
            realtimeJob.cancel()
            channel.unsubscribe()
            client.realtime.removeChannel(channel)
        }
    }.distinctUntilChanged()

    private suspend fun fetchPendingCommands(deviceId: String): List<DeviceCommand> {
        ensureAuthenticated()
        return client.from("device_commands").select {
            filter {
                eq("device_id", deviceId)
                eq("status", "pending")
            }
            order("created_at", Order.ASCENDING)
        }.decodeList()
    }

    suspend fun sendCommand(deviceId: String, command: CommandType) {
        ensureAuthenticated()
        client.from("device_commands").insert(DeviceCommand(deviceId = deviceId, command = command))
    }

    suspend fun acknowledgeCommand(commandId: Long) {
        ensureAuthenticated()
        client.from("device_commands").update(
            {
                set("status", "applied")
                set("applied_at", Instant.now().toString())
            },
        ) {
            filter { eq("id", commandId) }
        }
    }

    suspend fun heartbeat(status: DeviceStatus) {
        ensureAuthenticated()
        client.from("device_status").upsert(status)
    }

    suspend fun updateCurrentLocation(location: DeviceLocation) {
        ensureAuthenticated()
        client.from("device_locations").upsert(location)
    }

    suspend fun appendLocationHistory(location: DeviceLocation) {
        ensureAuthenticated()
        client.from("location_history").insert(location)
    }

    suspend fun fetchLocationHistory(
        deviceId: String,
        from: Instant,
        to: Instant,
        offset: Long,
        limit: Long = 50,
    ): LocationHistoryPage {
        ensureAuthenticated()
        val rows = client.from("location_history").select {
            filter {
                eq("device_id", deviceId)
                gte("recorded_at", from.toString())
                lte("recorded_at", to.toString())
            }
            order("recorded_at", Order.DESCENDING)
            range(offset..offset + limit)
        }.decodeList<LocationHistoryPoint>()
        return LocationHistoryPage(
            points = rows.take(limit.toInt()),
            hasMore = rows.size > limit,
        )
    }

    suspend fun fetchLocationRoute(
        deviceId: String,
        from: Instant,
        to: Instant,
        maxPoints: Int = 1500,
    ): List<LocationHistoryPoint> {
        ensureAuthenticated()
        return client.postgrest.rpc(
            function = "get_location_route",
            parameters = buildJsonObject {
                put("target_device_id", deviceId)
                put("from_time", from.toString())
                put("to_time", to.toString())
                put("max_points", maxPoints)
            },
        ).decodeList()
    }

    suspend fun deleteLocationHistory(
        receiverId: String,
        transmitterIds: Set<String>,
    ) {
        require(transmitterIds.isNotEmpty()) { "Seleziona almeno un trasmettitore" }
        ensureAuthenticated()
        client.postgrest.rpc(
            function = "delete_receiver_location_history",
            parameters = buildJsonObject {
                put("target_receiver_id", receiverId)
                put(
                    "target_transmitter_ids",
                    buildJsonArray {
                        transmitterIds.sorted().forEach(::add)
                    },
                )
            },
        )
    }

    suspend fun liveKitToken(deviceId: String, mode: String): LiveKitTokenResponse {
        ensureAuthenticated()
        return client.functions.invoke(
            function = "livekit-token",
            body = LiveKitTokenRequest(deviceId, mode),
        ).body()
    }

    companion object {
        private const val TAG = "FindMeRepository"
        // A single client and mutex prevent concurrent refresh-token rotation in one app process.
        private val processAuthenticationMutex = Mutex()
        private var lastSessionRefreshAtElapsedMs = 0L
        private val processClient: SupabaseClient by lazy { createClient() }

        private fun createClient(): SupabaseClient {
            check(AppConfig.supabaseUrl.isNotBlank()) { "SUPABASE_URL non configurato" }
            check(AppConfig.supabasePublishableKey.isNotBlank()) {
                "SUPABASE_PUBLISHABLE_KEY non configurata"
            }
            return createSupabaseClient(
                supabaseUrl = AppConfig.supabaseUrl,
                supabaseKey = AppConfig.supabasePublishableKey,
            ) {
                install(Auth) {
                    // Foreground lifecycle callbacks are unreliable for an always-on service.
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
                install(Postgrest)
                install(Realtime)
                install(Functions)
            }
        }
    }
}
