package com.elewashy.nexa.feature.downloads.data.di

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.data.DownloadRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the `DownloadRepository` interface to its singleton implementation.
 * Registered in the [SingletonComponent] so the repository outlives any one
 * Activity / Service — state and the download engine survive configuration
 * changes and service restarts.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadsModule {

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(
        impl: DownloadRepositoryImpl
    ): DownloadRepository
}
