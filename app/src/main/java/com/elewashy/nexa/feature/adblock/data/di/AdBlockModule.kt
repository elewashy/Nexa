package com.elewashy.nexa.feature.adblock.data.di

import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.feature.adblock.data.persistence.AdBlockStatsDao
import com.elewashy.nexa.feature.adblock.data.persistence.CustomRulesDao
import com.elewashy.nexa.feature.adblock.data.persistence.FilterListSettingsDao
import com.elewashy.nexa.feature.adblock.data.persistence.SiteSettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AdBlockModule {

    @Provides
    @Singleton
    fun provideFilterListSettingsDao(db: NexaDatabase): FilterListSettingsDao = db.filterListSettingsDao()

    @Provides
    @Singleton
    fun provideCustomRulesDao(db: NexaDatabase): CustomRulesDao = db.customRulesDao()

    @Provides
    @Singleton
    fun provideSiteSettingsDao(db: NexaDatabase): SiteSettingsDao = db.siteSettingsDao()

    @Provides
    @Singleton
    fun provideAdBlockStatsDao(db: NexaDatabase): AdBlockStatsDao = db.adBlockStatsDao()
}
