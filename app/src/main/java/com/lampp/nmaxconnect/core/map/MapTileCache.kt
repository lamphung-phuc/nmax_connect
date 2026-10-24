package com.lampp.nmaxconnect.core.map

import android.content.Context
import android.util.Log
import com.lampp.nmaxconnect.BuildConfig
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import vn.vietmap.vietmapsdk.Vietmap
import vn.vietmap.vietmapsdk.module.http.HttpRequestUtil
import vn.vietmap.vietmapsdk.offline.OfflineManager

/**
 * Sets up the SDK's network and disk cache. Styles, tiles and glyphs load through the proxy (`/maps/…`), which adds
 * the tile key and stamps max-age: VietMap sends no cache headers (checked 2026-10-17), so the SDK re-downloaded a
 * city's tiles (~0.5 MB) every time it came back on screen. Tile URLs are versioned (`/maps/tiles/vlc-20261015/…`),
 * so a long max-age never shows stale data. Measured on the owner's phone: repeat visit 6 tile requests → 0.
 */
object MapTileCache {
    private const val TAG = "MapTileCache"
    private const val CACHE_BYTES = 256L * 1024 * 1024 // SDK default is 50 MB

    // The SDK's own client allows 20 parallel requests per host; OkHttp's default is 5.
    private const val REQUESTS_PER_HOST = 20

    // 1.6.13 and older cached map URLs with the tile key in them; wiped once so no key stays on the phone.
    private const val PREFS = "map_tile_cache"
    private const val KEYLESS_DB = "keyless_db"

    fun install(context: Context, http: OkHttpClient) {
        Vietmap.getInstance(context.applicationContext) // first: the SDK's HTTP layer reads it on class init
        val proxyHost = BuildConfig.VIETMAP_PROXY_URL.toHttpUrlOrNull()?.host
        HttpRequestUtil.setOkHttpClient(
            http.newBuilder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = REQUESTS_PER_HOST })
                .addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(
                        if (request.url.host == proxyHost) {
                            request.newBuilder().header("X-App-Token", BuildConfig.VIETMAP_PROXY_TOKEN).build()
                        } else {
                            request
                        },
                    )
                }
                .build(),
        )
        val offline = OfflineManager.getInstance(context)
        val setSize = {
            offline.setMaximumAmbientCacheSize(CACHE_BYTES, logOnError("ambient cache size not set"))
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEYLESS_DB, false)) {
            setSize()
        } else {
            offline.resetDatabase(
                logOnError("old map cache not wiped") {
                    prefs.edit().putBoolean(KEYLESS_DB, true).apply()
                    setSize()
                },
            )
        }
    }

    private fun logOnError(what: String, onSuccess: () -> Unit = {}) = object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = onSuccess()
        override fun onError(message: String) {
            Log.w(TAG, "$what: $message")
        }
    }
}
