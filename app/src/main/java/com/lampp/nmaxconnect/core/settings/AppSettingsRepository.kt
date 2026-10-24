package com.lampp.nmaxconnect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.lampp.nmaxconnect.core.bike.BondedBike
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class AppSettings(
    val onboardingDone: Boolean = false,
    val bike: BondedBike? = null,
    val ccuBle: CcuBle? = null,
    /** Next-turn panel on the streamed map. */
    val dashTurns: Boolean = true,
    /** Bike stats panel (temps, fuel economy, battery, rpm) on the streamed map. */
    val dashStats: Boolean = false,
    /** Which stats the column shows (in [DashStat] order). */
    val dashStatItems: Set<DashStat> = DashStat.entries.toSet(),
    /** Connect on app launch; quit the app after a minute without the bike. */
    val autoConnect: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
) {
    /** Turns only go off while bike stats fill the column; with neither, the dash's + and − land mid-map. */
    val showTurns get() = dashTurns || !dashStats
}

/** Light / dark choice; SYSTEM follows the phone. */
enum class AppTheme { SYSTEM, LIGHT, DARK }

/** Bike stats the dash column can show. */
enum class DashStat { AIR, COOLANT, BATTERY, FUEL }

/** The CCU's LE address, learned from a `YCCU_<ccuId>` scan, so later connects skip scanning. */
data class CcuBle(val ccuId: String, val mac: String)

/** User choices that survive restarts. */
@Singleton
class AppSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it } // corrupt file → defaults
        .map { p ->
            val mac = p[BIKE_MAC]
            AppSettings(
                onboardingDone = p[ONBOARDING_DONE] ?: false,
                bike = mac?.let { BondedBike(name = p[BIKE_NAME] ?: it, mac = it, nickname = p[BIKE_NICKNAME]) },
                ccuBle = p[CCU_ID]?.let { id -> p[CCU_BLE_MAC]?.let { CcuBle(id, it) } },
                dashTurns = p[DASH_TURNS] ?: true,
                dashStats = p[DASH_STATS] ?: false,
                dashStatItems = p[DASH_STAT_ITEMS]?.mapNotNull { name -> DashStat.entries.find { it.name == name } }?.toSet()
                    ?: DashStat.entries.toSet(),
                autoConnect = p[AUTO_CONNECT] ?: false,
                theme = AppTheme.entries.find { it.name == p[THEME] } ?: AppTheme.SYSTEM,
            )
        }

    suspend fun setBike(bike: BondedBike) {
        store.edit {
            if (it[BIKE_MAC] != bike.mac) it.remove(BIKE_NICKNAME) // the name was for the other bike
            it[BIKE_MAC] = bike.mac
            it[BIKE_NAME] = bike.name
        }
    }

    /** Blank clears it, so the bike shows its ID again. */
    suspend fun setBikeNickname(name: String) {
        store.edit { if (name.isBlank()) it.remove(BIKE_NICKNAME) else it[BIKE_NICKNAME] = name.trim() }
    }

    suspend fun setCcuBle(ccu: CcuBle) {
        store.edit {
            it[CCU_ID] = ccu.ccuId
            it[CCU_BLE_MAC] = ccu.mac
        }
    }

    suspend fun setDashTurns(on: Boolean) {
        store.edit { it[DASH_TURNS] = on }
    }

    suspend fun setDashStats(on: Boolean) {
        store.edit { it[DASH_STATS] = on }
    }

    suspend fun setAutoConnect(on: Boolean) {
        store.edit { it[AUTO_CONNECT] = on }
    }

    suspend fun setTheme(theme: AppTheme) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setDashStat(stat: DashStat, on: Boolean) {
        store.edit {
            val current = it[DASH_STAT_ITEMS] ?: DashStat.entries.map(DashStat::name).toSet()
            it[DASH_STAT_ITEMS] = if (on) current + stat.name else current - stat.name
        }
    }

    suspend fun completeOnboarding(bike: BondedBike?) {
        store.edit {
            it[ONBOARDING_DONE] = true
            if (bike != null) {
                it[BIKE_MAC] = bike.mac
                it[BIKE_NAME] = bike.name
                // A new rider with a bike wants it linked on every launch; the link gives up after a minute without it.
                it[AUTO_CONNECT] = true
            }
        }
    }

    private companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val BIKE_MAC = stringPreferencesKey("bike_mac")
        val BIKE_NAME = stringPreferencesKey("bike_name")
        val BIKE_NICKNAME = stringPreferencesKey("bike_nickname")
        val CCU_ID = stringPreferencesKey("ccu_id")
        val CCU_BLE_MAC = stringPreferencesKey("ccu_ble_mac")
        val DASH_TURNS = booleanPreferencesKey("dash_turns")
        val DASH_STATS = booleanPreferencesKey("dash_stats")
        val DASH_STAT_ITEMS = stringSetPreferencesKey("dash_stat_items")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val THEME = stringPreferencesKey("theme")
    }
}
