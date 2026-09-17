@file:OptIn(
    io.github.jan.supabase.annotations.SupabaseExperimental::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package it.xcc.findme.core

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.selectAsFlow
import io.ktor.client.call.body
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class FindMeRepository(
    private val client: SupabaseClient = createClient(),
) {
    val isAuthenticated: Boolean get() = client.auth.currentUserOrNull() != null
    val currentUserId: String? get() = client.auth.currentUserOrNull()?.id

    suspend fun ensureAuthenticated() {
        client.auth.awaitInitialization()
        if (!isAuthenticated) client.auth.signInAnonymously()
    }

    suspend fun registerDevice(id: String, name: String, role: DeviceRole) {
        val ownerId = requireNotNull(currentUserId) { "Utente non autenticato" }
        client.from("devices").upsert(Device(id, ownerId, name, role))
    }

    suspend fun registerReceiver(id: String, name: String) {
        val ownerId = requireNotNull(currentUserId) { "Utente non autenticato" }
        client.from("receivers").upsert(ReceiverRegistration(id, ownerId, name))
    }

    fun receiverProfile(deviceId: String): Flow<ReceiverProfile?> =
        client.from("receivers")
            .selectAsFlow(ReceiverProfile::deviceId)
            .map { rows -> rows.firstOrNull { it.deviceId == deviceId } }

    suspend fun pairWithReceiver(transmitterDeviceId: String, pairingCode: String): PairDeviceResponse =
        client.functions.invoke(
            function = "pair-device",
            body = PairDeviceRequest(transmitterDeviceId, pairingCode.trim().uppercase()),
        ).body()

    suspend fun receiverAccess(
        transmitterDeviceId: String,
        answer: String? = null,
    ): ReceiverAccessResponse =
        client.functions.invoke(
            function = "receiver-access",
            body = ReceiverAccessRequest(transmitterDeviceId, answer),
        ).body()

    fun monitoredDevices(): Flow<List<MonitoredDevice>> {
        return client.from("receiver_transmitters")
            .selectAsFlow(ReceiverTransmitter::transmitterId)
            .flatMapLatest { relationships ->
                val aliasByDevice = relationships.associate {
                    it.transmitterId to it.alias?.trim()?.takeIf(String::isNotEmpty)
                }
                val devices = client.from("devices").selectAsFlow(Device::id)
                val statuses = client.from("device_status").selectAsFlow(DeviceStatus::deviceId)
                val locations = client.from("device_locations").selectAsFlow(DeviceLocation::deviceId)
                combine(devices, statuses, locations) { deviceRows, statusRows, locationRows ->
                    val statusByDevice = statusRows.associateBy(DeviceStatus::deviceId)
                    val locationByDevice = locationRows.associateBy(DeviceLocation::deviceId)
                    deviceRows
                        .filter { it.role == DeviceRole.TRANSMITTER }
                        .map {
                            MonitoredDevice(
                                device = it,
                                status = statusByDevice[it.id],
                                location = locationByDevice[it.id],
                                alias = aliasByDevice[it.id],
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

    fun commands(deviceId: String): Flow<List<DeviceCommand>> =
        client.from("device_commands")
            .selectAsFlow(DeviceCommand::id)
            .map { rows -> rows.filter { it.deviceId == deviceId } }

    suspend fun sendCommand(deviceId: String, command: CommandType) {
        client.from("device_commands").insert(DeviceCommand(deviceId = deviceId, command = command))
    }

    suspend fun acknowledgeCommand(commandId: Long) {
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
        client.from("device_status").upsert(status)
    }

    suspend fun updateLocation(location: DeviceLocation) {
        client.from("device_locations").upsert(location)
        client.from("location_history").insert(location)
    }

    suspend fun liveKitToken(deviceId: String, mode: String): LiveKitTokenResponse =
        client.functions.invoke(
            function = "livekit-token",
            body = LiveKitTokenRequest(deviceId, mode),
        ).body()

    companion object {
        fun createClient(): SupabaseClient {
            check(AppConfig.supabaseUrl.isNotBlank()) { "SUPABASE_URL non configurato" }
            check(AppConfig.supabasePublishableKey.isNotBlank()) {
                "SUPABASE_PUBLISHABLE_KEY non configurata"
            }
            return createSupabaseClient(
                supabaseUrl = AppConfig.supabaseUrl,
                supabaseKey = AppConfig.supabasePublishableKey,
            ) {
                install(Auth)
                install(Postgrest)
                install(Realtime)
                install(Functions)
            }
        }
    }
}
