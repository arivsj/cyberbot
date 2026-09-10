package com.cyberbot.mobile.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cyberbot.mobile.core.model.SessionSnapshot
import com.cyberbot.mobile.core.model.TransportMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "cyberbot_settings")

@Singleton
class SettingsStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val pcName = stringPreferencesKey("pc_name")
        val directBaseUrl = stringPreferencesKey("direct_base_url")
        val relayUrl = stringPreferencesKey("relay_url")
        val transportMode = stringPreferencesKey("transport_mode")
        val pinSet = stringPreferencesKey("pin_set")
        val irohEndpointId = stringPreferencesKey("iroh_endpoint_id")
        val irohTicket = stringPreferencesKey("iroh_ticket")
        val directCandidates = stringSetPreferencesKey("direct_candidates")
    }

    val session: Flow<SessionSnapshot> =
        context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences ->
                SessionSnapshot(
                    deviceId = preferences[Keys.deviceId],
                    pcName = preferences[Keys.pcName],
                    directBaseUrl = preferences[Keys.directBaseUrl] ?: "http://10.93.220.250:5000",
                    relayUrl = preferences[Keys.relayUrl].orEmpty(),
                    transportMode = preferences[Keys.transportMode]
                        ?.let(TransportMode::valueOf)
                        ?: TransportMode.AUTO,
                    pinSet = preferences[Keys.pinSet]?.toBooleanStrictOrNull() ?: false,
                    irohEndpointId = preferences[Keys.irohEndpointId].orEmpty(),
                    irohTicket = preferences[Keys.irohTicket].orEmpty(),
                    directCandidates = preferences[Keys.directCandidates]?.toList().orEmpty(),
                )
            }

    suspend fun read(): SessionSnapshot = session.first()

    suspend fun updatePairing(
        deviceId: String,
        pcName: String,
        directBaseUrl: String,
        relayUrl: String?,
        pinSet: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.deviceId] = deviceId
            preferences[Keys.pcName] = pcName
            preferences[Keys.directBaseUrl] = directBaseUrl
            preferences[Keys.relayUrl] = relayUrl.orEmpty()
            preferences[Keys.pinSet] = pinSet.toString()
        }
    }

    suspend fun updateCandidates(values: List<String>) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.directCandidates] = values.filter { it.isNotBlank() }.toSet()
        }
    }

    suspend fun updateIroh(endpointId: String, ticket: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.irohEndpointId] = endpointId.trim()
            preferences[Keys.irohTicket] = ticket.trim()
        }
    }

    /** Remove só o vínculo com o PC (mantém as preferências de interface). */
    suspend fun clearPairing() {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(Keys.deviceId)
            preferences.remove(Keys.pcName)
            preferences.remove(Keys.irohEndpointId)
            preferences.remove(Keys.irohTicket)
            preferences.remove(Keys.directCandidates)
        }
    }

    suspend fun updateDirectBaseUrl(value: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.directBaseUrl] = value.trim()
        }
    }

    suspend fun updateRelayUrl(value: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.relayUrl] = value.trim()
        }
    }

    suspend fun updateTransportMode(mode: TransportMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.transportMode] = mode.name
        }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { preferences -> preferences.clear() }
    }
}
