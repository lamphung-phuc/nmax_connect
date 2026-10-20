package com.lampp.nmaxconnect

import android.app.Application
import com.lampp.nmaxconnect.core.map.MapTileCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient

@HiltAndroidApp
class NmaxConnectApplication : Application() {
    @Inject lateinit var http: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        MapTileCache.install(this, http)
    }
}
