package com.github.lukelloyd1985.mytasklist.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.github.lukelloyd1985.mytasklist.data.remote.AppwriteAuthRepository
import com.github.lukelloyd1985.mytasklist.data.remote.AppwriteListRepository
import com.github.lukelloyd1985.mytasklist.data.remote.AppwriteTaskRepository
import com.github.lukelloyd1985.mytasklist.data.remote.AppwriteUserRepository
import com.github.lukelloyd1985.mytasklist.data.remote.AuthRepository
import com.github.lukelloyd1985.mytasklist.data.remote.ListRepository
import com.github.lukelloyd1985.mytasklist.data.remote.TaskRepository
import com.github.lukelloyd1985.mytasklist.data.remote.UserRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AppwriteAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: AppwriteUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindListRepository(impl: AppwriteListRepository): ListRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: AppwriteTaskRepository): TaskRepository
}
