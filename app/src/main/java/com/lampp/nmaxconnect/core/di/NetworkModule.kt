package com.lampp.nmaxconnect.core.di

import com.lampp.nmaxconnect.core.location.AndroidLocationSource
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.navigation.NavigationRepository
import com.lampp.nmaxconnect.core.navigation.Navigator
import com.lampp.nmaxconnect.core.vietmap.PlaceSearchApi
import com.lampp.nmaxconnect.core.vietmap.RouteApi
import com.lampp.nmaxconnect.core.vietmap.VietMapProxyClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** VietMap adds fields over time; ignore what we don't model. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun bindPlaceSearch(impl: VietMapProxyClient): PlaceSearchApi

    @Binds abstract fun bindRoute(impl: VietMapProxyClient): RouteApi

    @Binds abstract fun bindNavigator(impl: NavigationRepository): Navigator

    @Binds abstract fun bindLocation(impl: AndroidLocationSource): LocationSource
}
