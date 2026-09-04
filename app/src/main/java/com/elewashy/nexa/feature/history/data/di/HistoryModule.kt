package com.elewashy.nexa.feature.history.data.di

import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.history.data.HistoryRepository
import com.elewashy.nexa.feature.history.data.HistoryRepositoryImpl
import com.elewashy.nexa.feature.history.data.persistence.HistoryDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryModule {

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    companion object {
        @Provides
        @Singleton
        fun provideHistoryDao(db: NexaDatabase): HistoryDao = db.historyDao()
    }
}
