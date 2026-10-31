package com.borovas.animadroid.core.network

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun okHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
            .cookieJar(WebViewCookieJar())
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (req.header("User-Agent") == null) {
                        req.newBuilder().header("User-Agent", ANI_CLI_USER_AGENT).build()
                    } else {
                        req
                    },
                )
            }
            .addInterceptor(CloudflareInterceptor())
            .apply {
                if (debuggable) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()
    }
}
