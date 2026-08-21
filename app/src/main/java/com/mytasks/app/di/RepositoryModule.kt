package com.mytasks.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.mytasks.app.data.remote.AuthRepository
import com.mytasks.app.data.remote.FirebaseAuthRepository
import com.mytasks.app.data.remote.FirestoreListRepository
import com.mytasks.app.data.remote.FirestoreTaskRepository
import com.mytasks.app.data.remote.FirestoreUserRepository
import com.mytasks.app.data.remote.ListRepository
import com.mytasks.app.data.remote.TaskRepository
import com.mytasks.app.data.remote.UserRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FirestoreUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindListRepository(impl: FirestoreListRepository): ListRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: FirestoreTaskRepository): TaskRepository
}
