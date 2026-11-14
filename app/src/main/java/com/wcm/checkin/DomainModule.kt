package com.wcm.checkin

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 抓取单闸门的 Hilt 绑定：全应用共用一个 [CrawlService] 实例。 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideCrawlService(): CrawlService = CrawlService()
}
