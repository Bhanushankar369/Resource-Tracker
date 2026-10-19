package com.resourcetracker.di

import com.resourcetracker.BuildConfig
import com.resourcetracker.data.remote.BackendApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Long-lived scope for tiny app-wide tasks, e.g. re-arming the alarm from a
     *  BroadcastReceiver, which cannot suspend on its own. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // BASIC, never HEADERS: the backend no longer requires credentials, but
            // avoiding header logging keeps request URLs and any future auth material
            // out of logcat.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit {
        // Retrofit hard-fails at construction if the base URL is missing a trailing
        // slash, and a typo in .env is otherwise a very confusing crash.
        val baseUrl = BuildConfig.BACKEND_BASE_URL.let {
            if (it.isBlank()) "http://10.0.2.2:8000/" else if (it.endsWith("/")) it else "$it/"
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun backendApi(retrofit: Retrofit): BackendApi = retrofit.create(BackendApi::class.java)
}
