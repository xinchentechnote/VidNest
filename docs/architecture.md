# VidNest 架构设计

## 1. 技术选型（已核对当前稳定版本，2026-10）

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 9.8.2（wrapper） | 满足 AGP 9.4 的最低 9.6.0 要求 |
| Android Gradle Plugin | 9.4.1 | 内置 Kotlin 2.4.20，不再使用 kotlin-android 插件 |
| Kotlin | 内置 2.4.20 | Compose 插件用 org.jetbrains.kotlin.plugin.compose 2.4.21 |
| KSP | 2.3.12 | KSP 2.3.0+ 与内置 Kotlin 兼容（官方迁移文档确认）；禁用 kapt |
| Compose BOM | 2026.09.00 | Material 3 |
| Media3 | 1.11.1 | exoplayer + ui |
| Room | 2.8.5 | runtime/ktx/compiler/testing |
| core-ktx | 1.19.1 | |
| activity-compose | 1.13.0 | |
| navigation-compose | 2.10.2 | |
| lifecycle | 2.11.0 | viewmodel-compose / runtime-compose |
| datastore-preferences | 1.2.1 | 设置存储 |
| coroutines | 1.11.0 | + test |
| 测试 | junit 4.13.2 / robolectric 4.17 / androidx.test 1.7.0 / ext-junit 1.3.0 | |

- compileSdk = 36，targetSdk = 36，minSdk = 24（覆盖 Android 7.0+，兼容 Android 13 媒体权限与旧版权限机制）。
- 单模块 `:app`。MVP 规模下单模块足够，避免无价值的多模块拆分。

## 2. 分层与目录结构

```
app/src/main/java/com/vidnest/app/
  VidNestApp.kt                 // Application，持有 ServiceLocator
  MainActivity.kt               // 唯一 Activity，Compose 宿主
  data/
    local/                      // Room：entity / dao / database
    media/                      // MediaStoreVideoSource、SafVideoSource、DeviceVideoScanner、MediaStoreObserver
    repository/                 // VideoRepository、PlaybackRecordRepository、PlaybackSessionRepository
    settings/                   // SettingsRepository（DataStore）
  domain/
    model/                      // Video、Folder、WatchStatus、VideoSort、ResumeBehavior 等
    policy/                     // PlayCountPolicy、ProgressPolicy（纯函数，可单测）
  playback/
    PlayerController.kt         // 封装 ExoPlayer，暴露状态 Flow
    PlaybackCoordinator.kt      // 断点保存 + 会话计数编排
    ProgressWriter.kt           // 串行数据库写入器（防乱序）
  ui/
    navigation/                 // NavGraph
    library/                    // 库首页（文件夹 + 全部视频 + 搜索）
    folder/                     // 文件夹详情
    detail/                     // 视频详情
    player/                     // 播放器
    settings/                   // 设置
    components/                 // 通用组件（状态卡片、排序菜单等）
  ServiceLocator.kt             // 轻量手工 DI
  util/                         // 格式化等
```

职责边界：

- Compose UI 只消费 ViewModel 暴露的 StateFlow，不触碰数据库与扫描逻辑。
- ViewModel 通过 Repository（suspend / Flow API）读写数据，通过 PlayerController / PlaybackCoordinator 控制播放。
- 播放器控制、断点保存、播放计数三者分离：PlayerController（播放）、PlaybackCoordinator（编排保存与计数）、ProgressWriter（串行落库）。
- 计数与断点规则集中在 `domain/policy` 纯函数中，不散落在 UI。
- 不为分层而创建没有实现的接口；只有存在第二实现（如 VideoSource 有 MediaStore/SAF 两实现）才定义接口。

## 3. 依赖注入

构造器注入为主；Application 持有 `ServiceLocator`，在 `onCreate` 中按依赖顺序构造：Database → DAO → Repository → Scanner → Settings。ViewModel 通过 `viewModelFactory` 辅助函数从 ServiceLocator 取依赖。不引入 Hilt/Koin（项目规模不需要）。

## 4. 线程与并发模型

| 任务 | 执行位置 |
|---|---|
| MediaStore / SAF 扫描、DocumentFile 遍历 | Dispatchers.IO |
| Room 读写 | Room 自动切 IO；Repository 层 suspend |
| **断点/会话数据库写入** | **专用串行调度器** `Dispatchers.IO.limitedParallelism(1)`，所有写请求经 `ProgressWriter` 排队执行 |
| ExoPlayer 操作 | 主线程（Media3 要求）；UI 状态以 Flow 从主线程发出 |
| 进度 tick | PlayerController 内协程，playing 时每 500ms 更新 UI 流，每 5s 触发一次持久化 |

**防串号设计（关键）**：快速连续切换 A、B、C 视频时，UI 协程可能被取消、写入请求可能同时到达。措施：

1. 每个写入请求在**入队时刻**捕获完整快照（videoId、position、session 累计值），不引用共享可变状态。
2. 所有写经单一串行队列，顺序确定，不存在交错覆盖。
3. 切换/退出时先以 `NonCancellable` 投递 flush 请求，再释放播放器。
4. 数据库写入以 videoId 为主键 upsert，天然按视频隔离。

## 5. 导航

navigation-compose，路由：

- `library`（首页：文件夹 + 全部视频入口 + 搜索）
- `folder/{path}`（文件夹视频列表）
- `allVideos`（全部视频：搜索 + 排序 + 状态筛选）
- `detail/{videoId}`（视频详情）
- `player/{videoId}`（播放器）
- `settings`

播放器从文件夹/列表进入，返回时导航栈自然回到来源页（满足"退出播放器返回原文件夹"）。

## 6. 播放器生命周期

- `PlayerController` 由 `PlayerViewModel` 持有（viewModelScope），Activity 在 manifest 中声明 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"`，旋转不重建 Activity；即使系统强制重建，ViewModel 与播放器也存活，播放状态与计数会话不中断。
- Surface 通过 `PlayerView`（useController=false）+ Compose 覆盖层控制；PlayerView 在 DisposableEffect 中 attach/detach player。
- 沉浸式：播放器页隐藏系统栏（WindowInsetsControllerCompat），返回时恢复。
- `onStop` 时保存进度；`onCleared` 时 flush 后释放 ExoPlayer。

## 7. 错误处理与可恢复性

- 播放错误：`Player.Listener.onPlayerError` → 保存最后已知进度 → UI 显示错误 + 重试；`ERROR_CODE_IO_*` 权限/文件不存在时把该视频标记为 MISSING（仅索引状态，不删记录）。
- 扫描错误：单个 volume 查询失败只记录该 volume 失败，不影响其他数据；结果分批事务入库，任何一批失败保留已有数据。
- 权限：见 permissions-and-media-scanning.md；权限被撤销时 UI 进入授权引导态，不崩溃，不清理数据库。
