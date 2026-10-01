/**
 * @author Infinity
 * @description Repository condiviso per Supabase, tracking, comandi e contenuti multimediali.
 * @modified 01.10.2026 - Infinity | Aggiunta lettura e modifica della challenge ricevitore.
 * @modified 29.09.2026 - MDS | Aggiunta consegna persistente dei messaggi testuali.
 * @modified 29.09.2026 - MDS | Distinti lease UI, tracking persistente e disattivazione manuale.
 * @modified 23.09.2026 - MDS | Aggiunto polling di recovery dello stato tracking.
 */
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
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.client.call.body
import io.ktor.http.ContentType
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

    /** Recupera domanda e risposta visibili soltanto al proprietario del ricevitore. */
    suspend fun fetchReceiverAccessConfiguration(
        receiverId: String,
    ): ReceiverAccessConfiguration {
        ensureAuthenticated()
        return client.from("receiver_access_configurations").select {
            filter { eq("receiver_id", receiverId) }
            limit(1)
        }.decodeSingle()
    }

    /** Aggiorna atomicamente configurazione leggibile e hash usato dai trasmettitori. */
    suspend fun updateReceiverAccessConfiguration(
        receiverId: String,
        question: String,
        answer: String,
    ): ReceiverAccessConfiguration {
        val (normalizedQuestion, normalizedAnswer) =
            ReceiverAccessPolicy.normalize(question, answer)
        ensureAuthenticated()
        return client.postgrest.rpc(
            function = "update_receiver_access_configuration",
            parameters = buildJsonObject {
                put("target_receiver_id", receiverId)
                put("requested_question", normalizedQuestion)
                put("requested_answer", normalizedAnswer)
            },
        ).decodeSingle()
    }

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

    /**
     * Osserva configurazione e lease tracking usando Realtime con polling REST di sicurezza.
     */
    fun transmitterTrackingState(
        transmitterId: String,
    ): Flow<TrackingRuntimeState?> = channelFlow {
        suspend fun publishTrackingState(source: String) {
            val state = fetchTransmitterTrackingState(transmitterId)
            Log.d(TAG, "Tracking state check source=$source available=${state != null}")
            send(state)
        }

        publishTrackingState("initial")
        val realtimeJob = launch {
            runCatching {
                realtimeTransmitterTrackingState(transmitterId).collect(::send)
            }.onFailure {
                Log.e(TAG, "Tracking Realtime unavailable; REST polling remains active", it)
            }
        }
        try {
            while (isActive) {
                delay(TRACKING_STATE_POLL_INTERVAL_MS)
                publishTrackingState("poll")
            }
        } finally {
            realtimeJob.cancel()
        }
    }.distinctUntilChanged()

    /**
     * Costruisce il flusso Realtime originario per relazione e impostazioni ricevitore.
     */
    private fun realtimeTransmitterTrackingState(
        transmitterId: String,
    ): Flow<TrackingRuntimeState?> =
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

    /**
     * Recupera via REST lo stato tracking corrente per sopravvivere a websocket bloccati.
     */
    private suspend fun fetchTransmitterTrackingState(
        transmitterId: String,
    ): TrackingRuntimeState? {
        ensureAuthenticated()
        val relationship = client.from("receiver_transmitters").select {
            filter { eq("transmitter_id", transmitterId) }
            limit(1)
        }.decodeList<ReceiverTransmitter>().firstOrNull() ?: return null
        val settings = client.from("receivers").select {
            filter { eq("device_id", relationship.receiverId) }
            limit(1)
        }.decodeList<ReceiverTrackingSettings>().firstOrNull() ?: return null
        return TrackingRuntimeState(settings, relationship)
    }

    /**
     * Aggiorna il lease temporaneo senza cancellare un eventuale tracking persistente.
     */
    suspend fun setLiveTracking(
        receiverId: String,
        transmitterId: String,
        until: Instant?,
        liveHistory: Boolean,
    ) {
        ensureAuthenticated()
        client.from("receiver_transmitters").update({
            set("live_tracking_until", until?.toString())
            if (until != null) set("live_history", liveHistory)
        }) {
            filter {
                eq("receiver_id", receiverId)
                eq("transmitter_id", transmitterId)
            }
        }
        if (until == null) {
            client.from("receiver_transmitters").update({
                set("live_history", false)
            }) {
                filter {
                    eq("receiver_id", receiverId)
                    eq("transmitter_id", transmitterId)
                    eq("live_tracking_persistent", false)
                }
            }
        }
    }

    /**
     * Disattiva esplicitamente ogni modalità rapida, inclusa quella persistente.
     */
    suspend fun clearLiveTracking(receiverId: String, transmitterId: String) {
        ensureAuthenticated()
        client.from("receiver_transmitters").update({
            set("live_tracking_until", null as String?)
            set("live_tracking_persistent", false)
            set("live_history", false)
        }) {
            filter {
                eq("receiver_id", receiverId)
                eq("transmitter_id", transmitterId)
            }
        }
    }

    /**
     * Cambia lo storico rapido senza modificare il tracking rapido persistente.
     */
    suspend fun setLiveHistory(
        receiverId: String,
        transmitterId: String,
        enabled: Boolean,
    ) {
        ensureAuthenticated()
        client.from("receiver_transmitters").update({
            set("live_history", enabled)
        }) {
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

    suspend fun sendVoiceMessage(
        receiverId: String,
        transmitterId: String,
        messageId: String,
        audio: ByteArray,
        volume: VoiceMessageVolume,
        durationMs: Long,
    ): VoiceMessage {
        require(VoiceMessagePolicy.isValidDuration(durationMs)) {
            "Il messaggio deve durare da 1 a 60 secondi"
        }
        require(audio.isNotEmpty() && audio.size <= VoiceMessagePolicy.MAX_FILE_SIZE_BYTES) {
            "Dimensione del messaggio vocale non valida"
        }
        ensureAuthenticated()
        val path = VoiceMessagePolicy.storagePath(receiverId, transmitterId, messageId)
        val bucket = client.storage.from(VOICE_MESSAGES_BUCKET)
        bucket.upload(path, audio) {
            upsert = false
            contentType = ContentType("audio", "mp4")
        }
        return runCatching {
            client.postgrest.rpc(
                function = "create_voice_message",
                parameters = buildJsonObject {
                    put("target_receiver_id", receiverId)
                    put("target_transmitter_id", transmitterId)
                    put("target_message_id", messageId)
                    put("requested_volume", volume.name.lowercase())
                    put("requested_duration_ms", durationMs.toInt())
                },
            ).decodeSingle<VoiceMessage>()
        }.getOrElse { error ->
            runCatching { bucket.delete(path) }
            throw error
        }
    }

    /**
     * Crea atomicamente un messaggio testuale e il relativo comando remoto.
     */
    suspend fun sendTextMessage(
        receiverId: String,
        transmitterId: String,
        messageId: String,
        body: String,
    ): TextMessage {
        val normalized = TextMessagePolicy.normalize(body)
        ensureAuthenticated()
        return client.postgrest.rpc(
            function = "create_text_message",
            parameters = buildJsonObject {
                put("target_receiver_id", receiverId)
                put("target_transmitter_id", transmitterId)
                put("target_message_id", messageId)
                put("requested_body", normalized)
            },
        ).decodeSingle()
    }

    /** Recupera lo stato corrente di un messaggio testuale. */
    suspend fun fetchTextMessage(messageId: String): TextMessage {
        ensureAuthenticated()
        return client.from("text_messages").select {
            filter { eq("id", messageId) }
            limit(1)
        }.decodeSingle()
    }

    /** Aggiorna lo stato di visualizzazione del messaggio sul trasmettitore. */
    suspend fun updateTextMessageStatus(
        messageId: String,
        status: TextMessageStatus,
        errorMessage: String? = null,
    ) {
        ensureAuthenticated()
        client.from("text_messages").update({
            set("status", status.name.lowercase())
            set("error_message", errorMessage?.take(300))
            when (status) {
                TextMessageStatus.DISPLAYING ->
                    set("displayed_at", Instant.now().toString())
                TextMessageStatus.DISMISSED ->
                    set("dismissed_at", Instant.now().toString())
                else -> Unit
            }
        }) {
            filter { eq("id", messageId) }
        }
    }

    suspend fun fetchVoiceMessage(messageId: String): VoiceMessage {
        ensureAuthenticated()
        return client.from("voice_messages").select {
            filter { eq("id", messageId) }
            limit(1)
        }.decodeSingle()
    }

    suspend fun downloadVoiceMessage(message: VoiceMessage): ByteArray {
        ensureAuthenticated()
        return client.storage.from(VOICE_MESSAGES_BUCKET)
            .downloadAuthenticated(message.storagePath)
    }

    suspend fun updateVoiceMessageStatus(
        messageId: String,
        status: VoiceMessageStatus,
        errorMessage: String? = null,
    ) {
        ensureAuthenticated()
        client.from("voice_messages").update(
            {
                set("status", status.name.lowercase())
                set("error_message", errorMessage?.take(300))
                when (status) {
                    VoiceMessageStatus.PLAYING -> set("started_at", Instant.now().toString())
                    VoiceMessageStatus.COMPLETED,
                    VoiceMessageStatus.FAILED,
                    -> set("completed_at", Instant.now().toString())
                    else -> Unit
                }
            },
        ) {
            filter { eq("id", messageId) }
        }
    }

    suspend fun deleteVoiceMessageFile(storagePath: String) {
        ensureAuthenticated()
        client.storage.from(VOICE_MESSAGES_BUCKET).delete(storagePath)
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
        private const val VOICE_MESSAGES_BUCKET = "voice-messages"
        private const val TRACKING_STATE_POLL_INTERVAL_MS = 10_000L
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
                install(Storage)
            }
        }
    }
}
