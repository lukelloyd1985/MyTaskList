package com.github.lukelloyd1985.mytasklist.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.appwrite.Client
import io.appwrite.services.Account
import io.appwrite.services.Databases
import io.appwrite.services.Functions
import io.appwrite.services.Realtime
import javax.inject.Singleton
import com.github.lukelloyd1985.mytasklist.BuildConfig

@Module
@InstallIn(SingletonComponent::class)
object AppwriteModule {

    @Provides
    @Singleton
    fun provideAppwriteClient(@ApplicationContext context: Context): Client =
        Client(context)
            .setEndpoint(BuildConfig.APPWRITE_ENDPOINT)
            .setProject(BuildConfig.APPWRITE_PROJECT_ID)

    @Provides
    @Singleton
    fun provideAccount(client: Client): Account = Account(client)

    @Provides
    @Singleton
    fun provideDatabases(client: Client): Databases = Databases(client)

    @Provides
    @Singleton
    fun provideRealtime(client: Client): Realtime = Realtime(client)

    @Provides
    @Singleton
    fun provideFunctions(client: Client): Functions = Functions(client)
}
