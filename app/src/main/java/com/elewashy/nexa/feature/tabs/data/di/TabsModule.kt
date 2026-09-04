package com.elewashy.nexa.feature.tabs.data.di

import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.tabs.data.TabRepository
import com.elewashy.nexa.feature.tabs.data.TabRepositoryImpl
import com.elewashy.nexa.feature.tabs.data.persistence.TabsDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TabsModule {

    @Binds
    @Singleton
    abstract fun bindTabRepository(impl: TabRepositoryImpl): TabRepository

    companion object {
        @Provides
        @Singleton
        fun provideTabsDao(db: NexaDatabase): TabsDao = db.tabsDao()
    }
}
