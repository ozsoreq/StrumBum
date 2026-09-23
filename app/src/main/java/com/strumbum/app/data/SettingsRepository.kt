package com.strumbum.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.strumbum.app.music.NoteMath
import com.strumbum.app.music.Tunings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val a4Hz: Int = NoteMath.DEFAULT_A4.toInt(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledBlack: Boolean = false,
    val highContrast: Boolean = false,
    val haptics: Boolean = true,
    val tuningId: String = Tunings.STANDARD.id,
    val onboardingDone: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            a4Hz = (p[A4] ?: NoteMath.DEFAULT_A4.toInt()).coerceIn(NoteMath.MIN_A4, NoteMath.MAX_A4),
            themeMode = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            amoledBlack = p[AMOLED] ?: false,
            highContrast = p[HIGH_CONTRAST] ?: false,
            haptics = p[HAPTICS] ?: true,
            tuningId = p[TUNING] ?: Tunings.STANDARD.id,
            onboardingDone = p[ONBOARDED] ?: false,
        )
    }

    suspend fun setA4(hz: Int) = store.edit { it[A4] = hz.coerceIn(NoteMath.MIN_A4, NoteMath.MAX_A4) }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }
    suspend fun setAmoledBlack(on: Boolean) = store.edit { it[AMOLED] = on }
    suspend fun setHighContrast(on: Boolean) = store.edit { it[HIGH_CONTRAST] = on }
    suspend fun setHaptics(on: Boolean) = store.edit { it[HAPTICS] = on }
    suspend fun setTuning(id: String) = store.edit { it[TUNING] = id }
    suspend fun setOnboardingDone() = store.edit { it[ONBOARDED] = true }

    private companion object {
        val A4 = intPreferencesKey("a4_hz")
        val THEME = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled_black")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val HAPTICS = booleanPreferencesKey("haptics")
        val TUNING = stringPreferencesKey("tuning_id")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
    }
}
