package com.elewashy.nexa.feature.browser.data.search

import com.elewashy.nexa.core.data.persistence.NexaDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchSuggestionModule {
    @Binds
    @Singleton
    abstract fun bindSearchSuggestionRepository(
        implementation: SearchEngineSuggestionRepository,
    ): SearchSuggestionRepository

    @Binds
    @Singleton
    abstract fun bindSearchHistoryRepository(
        implementation: SearchHistoryRepositoryImpl,
    ): SearchHistoryRepository

    companion object {
        @Provides
        @Singleton
        fun provideSearchHistoryDao(database: NexaDatabase): SearchHistoryDao =
            database.searchHistoryDao()
    }
}
