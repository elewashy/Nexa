package com.elewashy.nexa.feature.share.data.di

import com.elewashy.nexa.feature.share.data.DefaultVideoExtractorRepository
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds [VideoExtractorRepository] in the singleton component. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShareModule {

    @Binds
    @Singleton
    abstract fun bindVideoExtractorRepository(
        impl: DefaultVideoExtractorRepository
    ): VideoExtractorRepository
}
