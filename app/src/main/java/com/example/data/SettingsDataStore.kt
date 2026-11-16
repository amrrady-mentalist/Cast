package com.example.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.random.Random

private val Context.beamDataStore: DataStore<Preferences> by preferencesDataStore(name = "beam_screen_prefs")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val TV_DISPLAY_NAME = stringPreferencesKey("tv_display_name")
        val QUALITY_PRESET = stringPreferencesKey("quality_preset")
        val REQUIRE_PIN = booleanPreferencesKey("require_pin")
        val PAIRING_PIN = stringPreferencesKey("pairing_pin")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val LOW_LATENCY_MODE = booleanPreferencesKey("low_latency_mode")
        val AUTO_START_RECEIVER = booleanPreferencesKey("auto_start_receiver")
    }

    private val defaultTvName: String =
        "${Build.MODEL.take(18).ifBlank { "Android" }} TV"

    val settingsFlow: Flow<BeamSettings> = context.beamDataStore.data.map { prefs ->
        BeamSettings(
            tvDisplayName = prefs[Keys.TV_DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: defaultTvName,
            qualityPreset = QualityPreset.fromId(prefs[Keys.QUALITY_PRESET]),
            requirePin = prefs[Keys.REQUIRE_PIN] ?: false,
            pairingPin = prefs[Keys.PAIRING_PIN] ?: generateDefaultPin(),
            keepScreenAwake = prefs[Keys.KEEP_SCREEN_AWAKE] ?: true,
            lowLatencyMode = prefs[Keys.LOW_LATENCY_MODE] ?: true,
            autoStartReceiver = prefs[Keys.AUTO_START_RECEIVER] ?: true
        )
    }

    suspend fun setTvDisplayName(name: String) {
        context.beamDataStore.edit { prefs ->
            prefs[Keys.TV_DISPLAY_NAME] = name.trim().take(28).ifBlank { defaultTvName }
        }
    }

    suspend fun setQualityPreset(preset: QualityPreset) {
        context.beamDataStore.edit { prefs ->
            prefs[Keys.QUALITY_PRESET] = preset.id
        }
    }

    suspend fun setRequirePin(requirePin: Boolean) {
        context.beamDataStore.edit { prefs ->
            prefs[Keys.REQUIRE_PIN] = requirePin
            if (prefs[Keys.PAIRING_PIN].isNullOrBlank()) {
                prefs[Keys.PAIRING_PIN] = generatePin()
            }
        }
    }

    suspend fun regeneratePin(): String {
        val newPin = generatePin()
        context.beamDataStore.edit { prefs ->
            prefs[Keys.PAIRING_PIN] = newPin
        }
        return newPin
    }

    suspend fun setKeepScreenAwake(enabled: Boolean) {
        context.beamDataStore.edit { prefs ->
            prefs[Keys.KEEP_SCREEN_AWAKE] = enabled
        }
    }

    suspend fun setLowLatencyMode(enabled: Boolean) {
        context.beamDataStore.edit { prefs ->
            prefs[Keys.LOW_LATENCY_MODE] = enabled
        }
    }

    suspend fun setAutoStartReceiver(enabled: Boolean) {
        context.beamDataStore.edit { prefs ->
            prefs[Keys.AUTO_START_RECEIVER] = enabled
        }
    }

    private fun generatePin(): String =
        Random.nextInt(1000, 10000).toString()

    private fun generateDefaultPin(): String =
        (1000 + (Build.MODEL.hashCode().let { kotlin.math.abs(it) } % 9000)).toString()
}
