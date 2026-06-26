package com.elewashy.nexa.feature.update.data.di

import com.elewashy.nexa.feature.update.data.GitHubUpdateRepository
import com.elewashy.nexa.feature.update.data.UpdateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(
        impl: GitHubUpdateRepository
    ): UpdateRepository
}
