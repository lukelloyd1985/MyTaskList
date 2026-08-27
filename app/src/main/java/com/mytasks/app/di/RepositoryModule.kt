package com.mytasks.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.mytasks.app.data.remote.AppwriteAuthRepository
import com.mytasks.app.data.remote.AppwriteListRepository
import com.mytasks.app.data.remote.AppwriteTaskRepository
import com.mytasks.app.data.remote.AppwriteUserRepository
import com.mytasks.app.data.remote.AuthRepository
import com.mytasks.app.data.remote.ListRepository
import com.mytasks.app.data.remote.TaskRepository
import com.mytasks.app.data.remote.UserRepository

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
