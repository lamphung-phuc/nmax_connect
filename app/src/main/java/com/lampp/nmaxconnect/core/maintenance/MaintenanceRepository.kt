package com.lampp.nmaxconnect.core.maintenance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wear parts tracked by distance, defaults close to the NMAX 155 service schedule.
 * Y-Connect gets its oil reminder from Yamaha's cloud (`maintereco`); this one is local.
 */
enum class ServiceItem(val defaultKm: Int) {
    ENGINE_OIL(3_000),
    GEAR_OIL(9_000),
    AIR_FILTER(12_000),
    CVT_BELT(24_000),
    SPARK_PLUG(12_000),
}

/** Older saves also hold an `epochDay`; the app's Json ignores unknown keys. */
@Serializable
data class ServiceRecord(val odoKm: Double)

data class Garage(
    /** Last ODO the control unit reported; survives restarts. */
    val odoKm: Double? = null,
    val services: Map<ServiceItem, ServiceRecord> = emptyMap(),
    /** Only the intervals the rider changed; the rest use [ServiceItem.defaultKm]. */
    val intervalsKm: Map<ServiceItem, Int> = emptyMap(),
) {
    fun intervalKm(item: ServiceItem) = intervalsKm[item] ?: item.defaultKm
}

/** Km at the last change; a part never recorded counts from 0 km (as delivered). */
fun Garage.lastChangeKm(item: ServiceItem): Double = services[item]?.odoKm ?: 0.0

/** Km left until [item] is due (negative = overdue), or null while the ODO is unknown. */
fun Garage.kmLeft(item: ServiceItem, odo: Double? = odoKm): Double? = odo?.let { lastChangeKm(item) + intervalKm(item) - it }

@Singleton
class MaintenanceRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val json: Json,
) {
    val garage: Flow<Garage> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            Garage(
                odoKm = p[ODO],
                services = p.services(),
                intervalsKm = p.intervals(),
            )
        }

    suspend fun saveOdometer(km: Double) = store.edit { it[ODO] = km }

    suspend fun saveService(item: ServiceItem, record: ServiceRecord, intervalKm: Int) = store.edit { p ->
        val intervals = p.intervals()
        p[SERVICES] = json.encodeToString(p.services() + (item to record))
        p[INTERVALS] = json.encodeToString(if (intervalKm == item.defaultKm) intervals - item else intervals + (item to intervalKm))
    }

    /** Sets or (null) clears [item]'s last change; reset and its undo. */
    suspend fun setRecord(item: ServiceItem, record: ServiceRecord?) = store.edit { p ->
        val services = p.services()
        p[SERVICES] = json.encodeToString(if (record == null) services - item else services + (item to record))
    }

    private fun Preferences.services() = decode<Map<ServiceItem, ServiceRecord>>(this[SERVICES]).orEmpty()

    private fun Preferences.intervals() = decode<Map<ServiceItem, Int>>(this[INTERVALS]).orEmpty()

    private inline fun <reified T> decode(s: String?): T? = s?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private companion object {
        val ODO = doublePreferencesKey("maint_odo_km")
        val SERVICES = stringPreferencesKey("maint_services")
        val INTERVALS = stringPreferencesKey("maint_intervals")
    }
}
