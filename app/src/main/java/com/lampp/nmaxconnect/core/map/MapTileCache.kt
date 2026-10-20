package com.lampp.nmaxconnect.core.map

import android.content.Context
import android.util.Log
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import vn.vietmap.vietmapsdk.Vietmap
import vn.vietmap.vietmapsdk.module.http.HttpRequestUtil
import vn.vietmap.vietmapsdk.offline.OfflineManager

/**
 * Makes the SDK's own disk tile cache work. VietMap tiles come with no Cache-Control, Expires, ETag or
 * Last-Modified (checked 2026-10-17), so the SDK re-downloaded a city's tiles (~0.5 MB) every time it came back
 * on screen. Tile URLs are versioned (`/maps/tiles/vlc-20261015/…`) and the style.json that names the version
 * keeps its ETag, so new map data arrives as new URLs: a long max-age on tiles never shows stale data. Glyph
 * ranges (`/maps/fonts/`) never change either; without it each cold start revalidated ~8 of them before labels.
 * Measured on the owner's phone: repeat visit to a city 6 tile requests → 0; after a restart 0 tiles.
 * style.json (~115 KB) was still fetched by every new map view; a day's max-age means new map data shows within a day.
 */
object MapTileCache {
    private const val TAG = "MapTileCache"
    private const val TILE_MAX_AGE_S = 30L * 24 * 60 * 60
    private const val STYLE_MAX_AGE_S = 24L * 60 * 60
    private const val CACHE_BYTES = 256L * 1024 * 1024 // SDK default is 50 MB

    // The SDK's own client allows 20 parallel requests per host; OkHttp's default is 5.
    private const val REQUESTS_PER_HOST = 20

    fun install(context: Context, http: OkHttpClient) {
        Vietmap.getInstance(context.applicationContext) // first: the SDK's HTTP layer reads it on class init
        HttpRequestUtil.setOkHttpClient(
            http.newBuilder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = REQUESTS_PER_HOST })
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val path = chain.request().url.encodedPath
                    val maxAge = when {
                        path.startsWith("/maps/tiles/") || path.startsWith("/maps/fonts/") -> TILE_MAX_AGE_S
                        path.startsWith("/maps/styles/") -> STYLE_MAX_AGE_S
                        else -> null
                    }
                    if (maxAge != null && response.isSuccessful && response.header("Cache-Control") == null) {
                        response.newBuilder().header("Cache-Control", "max-age=$maxAge").build()
                    } else {
                        response
                    }
                }
                .build(),
        )
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
            CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) {
                    Log.w(TAG, "ambient cache size not set: $message")
                }
            },
        )
    }
}
