package com.lampp.nmaxconnect.core.places

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lampp.nmaxconnect.core.location.GeoPoint
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A resolved destination. Stores coordinates, never a VietMap ref_id (those aren't permanent). */
@Serializable
data class SavedPlace(val name: String, val address: String, val point: GeoPoint)

data class SavedPlaces(
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val recents: List<SavedPlace> = emptyList(),
    /** Bookmarked by the rider, oldest first (chip order stays put). */
    val favorites: List<SavedPlace> = emptyList(),
)

@Singleton
class PlacesRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val json: Json,
) {
    val places: Flow<SavedPlaces> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            SavedPlaces(
                home = p[HOME]?.let(::decodePlace),
                work = p[WORK]?.let(::decodePlace),
                recents = p[RECENTS]?.let(::decodeList).orEmpty(),
                favorites = p[FAVORITES]?.let(::decodeList).orEmpty(),
            )
        }

    /** null clears Home. */
    suspend fun setHome(place: SavedPlace?) = store.edit { if (place == null) it.remove(HOME) else it[HOME] = json.encodeToString(place) }

    /** null clears Work. */
    suspend fun setWork(place: SavedPlace?) = store.edit { if (place == null) it.remove(WORK) else it[WORK] = json.encodeToString(place) }

    /** Most recent first, no duplicates (same spot within ~10 m), at most [MAX_RECENTS]. */
    suspend fun addRecent(place: SavedPlace) = store.edit { prefs ->
        val current = prefs[RECENTS]?.let(::decodeList).orEmpty()
        prefs[RECENTS] = json.encodeToString(withRecent(current, place))
    }

    /** Saves [place], or removes it if that spot is already a favorite. */
    suspend fun toggleFavorite(place: SavedPlace) = store.edit { prefs ->
        val current = prefs[FAVORITES]?.let(::decodeList).orEmpty()
        prefs[FAVORITES] = json.encodeToString(withFavoriteToggled(current, place))
    }

    private fun decodePlace(s: String): SavedPlace? = runCatching { json.decodeFromString<SavedPlace>(s) }.getOrNull()

    private fun decodeList(s: String): List<SavedPlace>? = runCatching { json.decodeFromString<List<SavedPlace>>(s) }.getOrNull()

    companion object {
        const val MAX_RECENTS = 10
        private val HOME = stringPreferencesKey("place_home")
        private val WORK = stringPreferencesKey("place_work")
        private val RECENTS = stringPreferencesKey("place_recents")
        private val FAVORITES = stringPreferencesKey("place_favorites")

        fun isFavorite(favorites: List<SavedPlace>, place: SavedPlace) = favorites.any { it.point.isNear(place.point) }

        fun withFavoriteToggled(current: List<SavedPlace>, place: SavedPlace): List<SavedPlace> =
            if (isFavorite(current, place)) current.filterNot { it.point.isNear(place.point) } else current + place

        fun withRecent(current: List<SavedPlace>, place: SavedPlace): List<SavedPlace> =
            (listOf(place) + current.filterNot { it.point.isNear(place.point) }).take(MAX_RECENTS)

        // 0.0001° ≈ 11 m: picking the same spot twice (e.g. from search and from recents) is one entry.
        private fun GeoPoint.isNear(o: GeoPoint) = kotlin.math.abs(lat - o.lat) < 1e-4 && kotlin.math.abs(lng - o.lng) < 1e-4
    }
}
