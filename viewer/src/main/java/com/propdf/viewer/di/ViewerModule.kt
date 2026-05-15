package com.propdf.viewer.di

import android.content.Context
import com.propdf.viewer.cache.PageCacheManager
import com.propdf.viewer.render.AsyncPageRenderer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(ViewModelComponent::class)
object ViewerModule {

    @Provides
    fun providePageCacheManager(): PageCacheManager {
        return PageCacheManager(maxMemoryMB = 128)
    }

    @Provides
    fun provideAsyncPageRenderer(
        @ApplicationContext context: Context,
        cacheManager: PageCacheManager
    ): AsyncPageRenderer {
        return AsyncPageRenderer(
            context = context,
            cacheManager = cacheManager,
            maxConcurrentRenders = 2
        )
    }
}
