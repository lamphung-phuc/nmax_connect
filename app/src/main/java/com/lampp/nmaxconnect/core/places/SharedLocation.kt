package com.lampp.nmaxconnect.core.places

import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.location.GeoPoint
import java.io.IOException
import java.net.URLDecoder
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A place shared into the app (Google Maps "Share", or any `geo:` link). Google Maps and VietMap are both
 * WGS-84 in Vietnam (Google's GCJ-02 shift is China-only), so [point] is used as-is, never converted.
 * [point] is null when only a name came through: Google's place shares expand to `?q=<name, address>&ftid=…`
 * and no public response carries the pin (checked 2026-10-17), so the place is matched on VietMap instead
 * ([searchText], [matches]).
 */
data class SharedLocation(val name: String?, val address: String?, val point: GeoPoint?, val plusCode: String? = null) {
    /** VietMap query for a place without a point: its name plus "<house number> <street>" when Google gave one. */
    val searchText: String?
        get() = name?.let { n ->
            listOfNotNull(n, address?.substringBefore(',')?.takeIf { it.first().isDigit() }).joinToString(" ")
        }

    /**
     * Whether a VietMap hit is plausibly this place: a distinctive name word in common, in the city Google
     * gave. The city check stops "Khách sạn Lotte Hà Nội" landing on Lotte Sài Gòn (probed live 2026-10-17).
     */
    fun matches(hitName: String, hitAddress: String): Boolean = name != null && sameName(name, hitName) && inSharedCity(hitAddress)

    /** Whether a VietMap address ("…, Thành phố Hà Nội") is in the city / province Google gave. */
    fun inSharedCity(hitAddress: String): Boolean {
        val city = squash(hitAddress.substringAfterLast(',')).removePrefix("thanhpho").removePrefix("tinh")
        return city.isNotEmpty() && city in squash(listOfNotNull(name, address).joinToString(" "))
    }

    /** "Xuân Mai, Hà Nội" from "Unnamed Road, Xuân Mai, Hà Nội 100000, Việt Nam": a VietMap query for the area. */
    val locality: String?
        get() = address?.split(',')
            ?.map { it.replace(digits, "").trim() }
            ?.filter { it.isNotEmpty() && squash(it) !in notPlaces }
            ?.takeLast(2)?.joinToString(", ")?.ifEmpty { null }

    companion object {
        private const val NUM = """(-?\d{1,3}\.\d+)"""
        private const val SEP = """\s*,\s*\+?\s*"""

        // Most precise first. `!3d…!4d…` is the place's own pin; `@lat,lng` is only the camera centre.
        private val pin = Regex("""!3d$NUM!4d$NUM""")
        private val pointPatterns = listOf(
            Regex("""[?&](?:q|query|ll|destination|daddr)=$NUM$SEP$NUM"""),
            Regex("""/maps/(?:search|place)/$NUM$SEP$NUM"""),
            Regex("""geo:$NUM,$NUM"""),
        )
        private val camera = Regex("""@$NUM,$NUM""")
        private val coordinates = Regex("""$NUM$SEP$NUM""")
        private val link = Regex("""(?:https?://|geo:)\S+""")
        private val queryName = Regex("""[?&](?:q|query)=([^&]+)""")
        private val placeName = Regex("""/maps/place/([^/@?]+)""")
        private val geoLabel = Regex("""\(([^)]+)\)""")

        /** Reads coordinates and a name from the shared text, links included (no network). */
        fun parse(text: String): SharedLocation {
            val lines = text.replace(link, "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
            // One line is "<name>, <address>" like q= below; several are name, then address.
            val plain = lines.singleOrNull()?.split(",", limit = 2)?.map { it.trim() } ?: lines
            val urls = link.findAll(text).joinToString("\n") { decode(it.value) }
            // Google's place links carry "<name>, <address>" in q=.
            val fromUrl = urlName(urls)?.split(",", limit = 2)?.map { it.trim() }
            val plusCode = PlusCode.find(plain.joinToString("\n")) ?: PlusCode.find(urls)

            // Google names code-addressed places "RH6J+F7M Camping Đồi Bù"; the code is kept apart.
            fun String.withoutCode() = (plusCode?.let { replace(it, "") } ?: this).trim(' ', ',').ifEmpty { null }
            val name = (plain.getOrNull(0) ?: fromUrl?.get(0))?.withoutCode()
            val point = pin.findAll(urls).lastOrNull()?.toPoint()
                ?: pointPatterns.firstNotNullOfOrNull { p -> p.findAll(urls).firstNotNullOfOrNull { it.toPoint() } }
                ?: plusCode?.takeIf(PlusCode::isFull)?.let(PlusCode::decode)
                // The camera centre can sit off the place; trust it only when there is no name to search for.
                ?: camera.find(urls)?.toPoint()?.takeIf { name == null && plusCode == null }
            val address = (plain.getOrNull(1) ?: fromUrl?.getOrNull(1))?.withoutCode()
            return SharedLocation(name = name, address = address, point = point, plusCode = plusCode)
        }

        /**
         * Google and VietMap name places differently ("Lotte Hotel Hanoi" / "Khách sạn Lotte Hà Nội"), so one
         * shared distinctive word is enough; generic words (hotel, city names) don't count.
         */
        fun sameName(a: String, b: String): Boolean = words(a).intersect(words(b)).isNotEmpty()

        private fun words(s: String): Set<String> = plain(s).split(nonWord).filter { it.length >= 3 && it !in generic }.toSet()

        /** "Thành phố Hà Nội" → "thanhphohanoi", so "Hanoi" and "Hà Nội" compare equal. */
        private fun squash(s: String): String = plain(s).replace(nonWord, "")

        private fun plain(s: String): String =
            Normalizer.normalize(s.lowercase().replace('đ', 'd'), Normalizer.Form.NFD).replace(marks, "")

        private val digits = Regex("""\d+""")
        private val notPlaces = setOf("vietnam", "unnamedroad", "unnamed", "road")
        private val marks = Regex("""\p{M}+""")
        private val nonWord = Regex("""[^a-z0-9]+""")
        private val generic = setOf(
            "khach", "san", "hotel", "nha", "hang", "quan", "cafe", "coffee", "phe", "cong", "cua", "sieu", "thi",
            "benh", "vien", "truong", "cho", "the", "and", "duong", "phuong", "pho", "thanh", "noi", "hanoi", "chi",
            "minh", "hcm", "saigon", "gon", "nang", "viet", "nam", "vietnam",
        )

        /** A Google short link that has to be expanded before it says where it points. */
        fun shortLink(text: String): String? = link.findAll(text).map { it.value }.firstOrNull { url ->
            url.toHttpUrlOrNull()?.host?.let { it == "goo.gl" || it.endsWith(".goo.gl") || it == "g.co" } == true
        }

        private fun urlName(urls: String): String? =
            (geoLabel.find(urls) ?: placeName.find(urls) ?: queryName.find(urls))
                ?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && !coordinates.matches(it) }

        @Suppress("SwallowedException") // a stray '%' in someone's text: match against it undecoded
        private fun decode(url: String): String = try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: IllegalArgumentException) {
            url
        }

        // NUM only matches plain decimals, so toDouble can't throw.
        private fun MatchResult.toPoint(): GeoPoint? = GeoPoint(groupValues[1].toDouble(), groupValues[2].toDouble())
            .takeIf { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && !(it.lat == 0.0 && it.lng == 0.0) }
    }
}

/** Expands Google short links (redirect headers only, never the page) and parses the result. */
class SharedLocationResolver @Inject constructor(
    http: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun resolve(text: String): SharedLocation {
        val shared = SharedLocation.parse(text)
        val short = SharedLocation.shortLink(text)?.takeIf { shared.point == null } ?: return shared
        val expanded = SharedLocation.parse(expand(short).joinToString("\n"))
        // Google's app shares "<name>\n<short link>": the address (and so the city) only comes with the link.
        return SharedLocation(
            name = shared.name ?: expanded.name,
            address = shared.address ?: expanded.address,
            point = expanded.point,
            plusCode = shared.plusCode ?: expanded.plusCode,
        )
    }

    @Suppress("SwallowedException") // offline: keep what the text itself says (a name still opens search)
    private suspend fun expand(link: String): List<String> = try {
        withContext(ioDispatcher) { redirects(link) }
    } catch (e: IOException) {
        emptyList()
    }

    /** Follows Google's own redirects, stopping at the first URL with coordinates or that leaves Google. */
    private fun redirects(start: String): List<String> {
        val hops = mutableListOf<String>()
        var url = start.toHttpUrlOrNull()?.takeIf { isGoogleHost(it.host) }
        while (url != null && hops.size < MAX_HOPS) {
            val from = url
            val next = noRedirects.newCall(Request.Builder().url(from).build()).execute().use { r ->
                r.header("Location")?.takeIf { r.isRedirect }?.let(from::resolve)
            }
            next?.let { hops += it.toString() }
            url = next?.takeIf { isGoogleHost(it.host) && SharedLocation.parse(it.toString()).point == null }
        }
        return hops
    }

    private companion object {
        const val MAX_HOPS = 5
    }
}

/** Hosts whose redirects a shared link may follow; an explicit list, so `google.attacker.com` isn't one. */
internal fun isGoogleHost(host: String) =
    listOf("google.com", "google.com.vn", "goo.gl", "g.co").any { host == it || host.endsWith(".$it") }

/** Shares land on the Activity; the Navigate screen takes them from here once the app is ready. */
@Singleton
class SharedLocationInbox @Inject constructor() {
    private val pending = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = pending.asStateFlow()

    fun offer(text: String?) {
        if (!text.isNullOrBlank()) pending.value = text
    }

    fun take(): String? = pending.getAndUpdate { null }
}
