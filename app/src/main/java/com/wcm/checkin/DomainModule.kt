package com.wcm.checkin

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import android.content.Context
import javax.inject.Singleton

/**
 * 组合根：全应用唯一的对象图装配点。
 *
 * 为什么不用 `@Inject constructor` 让 Hilt 自动构造 `:domain` 的服务：
 * `:domain` 刻意不引入 DI 框架（`domain/build.gradle.kts` 里没有 Hilt / javax.inject），
 * 这样它能独立秒级编译、也能在 JVM 单测里直接 `CheckinService(fakeAccount, ...)`。
 * 代价是依赖关系写在这里 —— 反而是好事：漏一条就编译不过，不会像注解那样隐式装配。
 *
 * **所有 `@Provides` 都必须 `@Singleton`**。尤其是 [CrawlService]：它的「单闸门」
 * 是实例状态（`Mutex` + `StopFlag`），多实例 = 两个抓取同时写同一 SAF 目录，
 * 会产生 `xxx (1).jpg` 副本并互相覆盖进度。现有测试没有一个覆盖这一点，
 * 所以漏标 scope 不会被自动化发现。
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    // ========================= 端口（:core 定义，:data 实现） =========================

    @Provides
    @Singleton
    fun provideAccountPort(): AccountPort = AccountStoreAdapter()

    @Provides
    @Singleton
    fun provideCrawlSettingsPort(): CrawlSettingsPort = CrawlSettingsAdapter()

    @Provides
    @Singleton
    fun provideTopicCachePort(): TopicCachePort = TopicCacheAdapter()

    @Provides
    @Singleton
    fun providePanelPort(): PanelPort = PanelSettingsAdapter()

    @Provides
    @Singleton
    fun provideCredentialPort(): CredentialPort = CredentialVaultAdapter()

    // ========================= 服务（构造注入） =========================

    @Provides
    @Singleton
    fun provideAccountMaintenance(
        account: AccountPort,
        panel: PanelPort,
        vault: CredentialPort,
    ): AccountMaintenanceService = AccountMaintenanceService(account, panel, vault)

    @Provides
    @Singleton
    fun provideAutoRelogin(
        account: AccountPort,
        vault: CredentialPort,
    ): AutoRelogin = AutoRelogin(account, vault)

    @Provides
    @Singleton
    fun provideCheckinService(
        account: AccountPort,
        maintenance: AccountMaintenanceService,
        topicCache: TopicCachePort,
    ): CheckinService = CheckinService(account, maintenance, topicCache)

    @Provides
    @Singleton
    fun provideCrawlSaver(settings: CrawlSettingsPort): CrawlSaver = CrawlSaver(settings)

    @Provides
    @Singleton
    fun provideCrawlEngine(
        settings: CrawlSettingsPort,
        account: AccountPort,
        saver: CrawlSaver,
    ): CrawlEngine = CrawlEngine(settings, account, saver)

    @Provides
    @Singleton
    fun provideTimelineCrawler(
        settings: CrawlSettingsPort,
        account: AccountPort,
        engine: CrawlEngine,
        saver: CrawlSaver,
    ): TimelineCrawler = TimelineCrawler(settings, account, engine, saver)

    @Provides
    @Singleton
    fun provideAlbumCrawler(
        settings: CrawlSettingsPort,
        account: AccountPort,
        engine: CrawlEngine,
        saver: CrawlSaver,
    ): AlbumCrawler = AlbumCrawler(settings, account, engine, saver)

    @Provides
    @Singleton
    fun provideCrawlService(
        settings: CrawlSettingsPort,
        timeline: TimelineCrawler,
        album: AlbumCrawler,
        engine: CrawlEngine,
    ): CrawlService = CrawlService(settings, timeline, album, engine)

    // ========================= 超话互动任务 =========================

    /**
     * 存储实现直接暴露成两个端口。
     *
     * 没有像其他端口那样再套一层 `PortAdapters`：`EngageStore` 的方法签名本来就是
     * 照着窄端口设计的（没有默认参数，不需要为抽象改调用点），再套一层只是多一跳。
     */
    @Provides
    @Singleton
    fun provideEngageStore(@ApplicationContext context: Context): EngageStore = EngageStore(context)

    @Provides
    @Singleton
    fun provideEngageConfigPort(store: EngageStore): EngageConfigPort = store

    @Provides
    @Singleton
    fun provideEngageRunPort(store: EngageStore): EngageRunPort = store

    /** 账号读取：按 uid 取一份可用的 cookie。 */
    @Provides
    @Singleton
    fun provideEngageAccountSource(
        account: AccountPort,
        @ApplicationContext context: Context,
    ): EngageAccountSource = EngageAccountSource { uid ->
        account.load(context).firstOrNull { it.uid == uid }?.let {
            EngageAccount(
                uid = it.uid,
                name = it.name,
                cookie = it.cookie,
                cookieDomains = it.cookieDomains,
                dead = it.cookieDead,
            )
        }
    }

    /** 换发回写：一轮结束后把会话里吸收到的 Set-Cookie 落回账号。 */
    @Provides
    @Singleton
    fun provideEngageAccountSink(
        account: AccountPort,
        @ApplicationContext context: Context,
    ): EngageAccountSink = EngageAccountSink { uid, cookie, domains ->
        account.updateCookie(context, uid, cookie, domains)
    }

    /**
     * 必须 `@Singleton`：它持有实例级 `Mutex`，多一个实例就多一把闸门，
     * 定时任务与手动补跑会同时开跑，导致同一动作被执行两次。
     */
    @Provides
    @Singleton
    fun provideEngageService(
        config: EngageConfigPort,
        runs: EngageRunPort,
        source: EngageAccountSource,
        sink: EngageAccountSink,
    ): EngageService = EngageService(config, runs, source, sink)
}
