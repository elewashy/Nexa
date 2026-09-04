package com.elewashy.nexa.feature.bookmarks.data.di

import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.bookmarks.data.BookmarkRepository
import com.elewashy.nexa.feature.bookmarks.data.BookmarkRepositoryImpl
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarksDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BookmarksModule {

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    companion object {
        @Provides
        @Singleton
        fun provideBookmarksDao(db: NexaDatabase): BookmarksDao = db.bookmarksDao()
    }
}
