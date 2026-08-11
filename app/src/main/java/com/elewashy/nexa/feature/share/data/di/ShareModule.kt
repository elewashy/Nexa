package com.elewashy.nexa.feature.share.data.di

import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.share.data.DefaultVideoExtractorRepository
import com.elewashy.nexa.feature.share.data.VideoExtractorRepository
import com.elewashy.nexa.feature.share.data.YouTubeExtractor
import com.elewashy.nexa.feature.share.data.platform.FacebookVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.InstagramVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.PlatformVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.ThreadsVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.TikTokVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.TwitterVideoExtractor
import com.elewashy.nexa.feature.share.data.platform.YouTubeVideoExtractor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ShareModule {

    @Binds
    @Singleton
    abstract fun bindVideoExtractorRepository(impl: DefaultVideoExtractorRepository): VideoExtractorRepository

    @Binds @IntoSet
    abstract fun bindFacebookExtractor(impl: FacebookVideoExtractor): PlatformVideoExtractor

    @Binds @IntoSet
    abstract fun bindInstagramExtractor(impl: InstagramVideoExtractor): PlatformVideoExtractor

    @Binds @IntoSet
    abstract fun bindThreadsExtractor(impl: ThreadsVideoExtractor): PlatformVideoExtractor

    @Binds @IntoSet
    abstract fun bindTikTokExtractor(impl: TikTokVideoExtractor): PlatformVideoExtractor

    @Binds @IntoSet
    abstract fun bindTwitterExtractor(impl: TwitterVideoExtractor): PlatformVideoExtractor

    @Binds @IntoSet
    abstract fun bindYouTubeExtractor(impl: YouTubeVideoExtractor): PlatformVideoExtractor

    companion object {
        /** Singleton so the scraped auth token is fetched once per process. */
        @Provides
        @Singleton
        fun provideYouTubeBackend(httpClientProvider: HttpClientProvider): YouTubeExtractor =
            YouTubeExtractor(httpClientProvider)
    }
}
