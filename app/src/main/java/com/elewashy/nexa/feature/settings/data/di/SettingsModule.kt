package com.elewashy.nexa.feature.settings.data.di

import com.elewashy.nexa.feature.settings.data.DefaultThemeRepository
import com.elewashy.nexa.feature.settings.data.ThemeRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [ThemeRepository] to [DefaultThemeRepository] in the singleton component.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindThemeRepository(
        impl: DefaultThemeRepository
    ): ThemeRepository
}
