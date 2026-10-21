# VidNest

一款完全离线运行的 Android 本地视频管理与播放器。

**核心能力：扫描手机视频 + 按真实文件夹浏览 + 每个视频独立断点续播 + 每个视频独立播放次数统计 + 倍速播放。**

不依赖服务器、账号、云服务或网络 API；视频文件、播放历史、断点与计数全部保存在本地。应用不会修改、移动或删除任何原始视频文件。

## 功能清单

- **媒体扫描**：基于 MediaStore 全量扫描 + ContentObserver 防抖增量更新；分批事务入库，重复扫描幂等；文件消失只标 MISSING（不清记录），移动/重命名在唯一同名同大小时迁移播放记录；扫描失败/权限撤销不清空数据、不崩溃。
- **文件夹浏览**：按真实目录层级归类（API 29+ 用 `RELATIVE_PATH`，旧版本由 `DATA` 推导）；文件夹展示数量与总大小；视频展示时长/大小/分辨率/播放次数/观看状态；名称搜索 + 6 种排序。
- **逐视频断点**：每视频独立进度持久化（Room）；5s 周期保存 + 暂停/切换/退出/错误/结束时机立即保存；恢复时校验无效进度与时长变化；播放结束标记"已看完"并归零断点；快速切换视频不串号（串行写入器保证）。
- **播放次数**：会话制计数——实际播放满 `min(10s, 时长/3)` 计一次；暂停/拖动/倍速不计数；播放器重建、旋转不重复计数；进程被杀后领养未计数会话继续累计（不丢失不重复）；已看完重播再次计数。
- **倍速播放**：0.5x ~ 3.0x 共 9 档，切换不重置位置；可记住上次倍速。
- **播放器**：播放/暂停、进度条 seek、±10s、时间显示、倍速面板、自动隐藏控制层、沉浸式 + 常亮、断点恢复提示、错误重试、横竖屏切换、退出返回原文件夹。
- **观看状态**：未播放 / 正在观看 / 已看完，全局统一规则；视频详情页展示完整统计。
- **本地设置**：重新扫描、权限状态、默认排序、断点恢复行为（总是/询问/从不）、结束后行为（从头/询问）、记住倍速、播放次数规则说明、清理失效索引（不动原始文件）、版本信息。
- **SAF 授权目录**（可选）：MediaStore 覆盖不到的目录可通过文档树授权访问，持久化授权、失效提示、移除时清理索引。

## 技术栈

Kotlin · Jetpack Compose + Material 3 · Media3 ExoPlayer 1.11.1 · Room 2.8.5 · Coroutines + Flow · DataStore · Gradle Kotlin DSL（AGP 9.4.1 / Gradle 9.8.2 / 独立 KGP 2.3.21 / KSP 2.3.12）

- minSdk 24（Android 7.0+），targetSdk 36，compileSdk 37
- 权限最小化：仅 `READ_MEDIA_VIDEO`（Android 13+）与 `READ_EXTERNAL_STORAGE`（≤Android 12L）；不申请 MANAGE_EXTERNAL_STORAGE
- 分层架构：UI（Compose）→ ViewModel → Repository / PlayerController → Room / MediaStore；策略纯函数集中在 `domain/policy`；详细设计见 [docs/](docs/)（需求、架构、数据模型、权限与扫描、播放与断点、测试计划）

## 构建与安装

 Requirements：JDK 17+，Android SDK（Platform 37）。

```bash
# 本地测试（无需设备）
./gradlew testDebugUnitTest

# Debug APK（可直接安装）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release APK（本仓库未配置签名，产物为未签名 APK）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
# 签名安装（示例）：
# apksigner sign --ks your.jks --out vidnest.apk app-release-unsigned.apk
```

Android Studio 直接打开项目根目录即可运行。

### CI / 发布（GitHub Actions）

- **CI**（`.github/workflows/ci.yml`）：push 到 main 或 PR 时自动运行单元测试 + lint + 构建 Debug APK（Artifact 可下载）。
- **Release**（`.github/workflows/release.yml`）：推送 `v*` 标签或在 Actions 页手动触发 → 运行测试 → 构建签名 Release APK + Debug APK → 自动创建 GitHub Release 并附上 APK。

```bash
# 打补丁版本发布
git tag v1.0.1
git push origin v1.0.1

# 或：GitHub 仓库页 → Actions → Release → Run workflow
```

签名配置：CI 使用仓库 Secrets 中的密钥（`VIDNEST_KEYSTORE_BASE64/PASSWORD/ALIAS/KEY_PASSWORD`），签名配置在 `app/build.gradle.kts` 中由环境变量驱动——本地无密钥时构建未签名 APK，设置环境变量 `VIDNEST_KEYSTORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD` 即可本地出签名包。

> 私有仓库的 Actions 消耗免费额度分钟数（免费版 2000 分钟/月，本项目单次 CI 约 5-8 分钟）。

## 自动化测试

| 类型 | 命令 | 状态 |
|---|---|---|
| 单元/数据库测试（JUnit + Robolectric + Room in-memory） | `./gradlew testDebugUnitTest` | 69 个测试全部通过 |
| 设备测试（Room 真机链路冒烟） | `./gradlew connectedDebugAndroidTest`（需设备/模拟器） | 已在 API 36 模拟器通过 |
| 静态检查 | `./gradlew lintDebug` | 0 错误，29 个警告（均不阻塞） |

单元测试重点覆盖：断点隔离（A/B/C 快速切换防串号、并发交错写入、同事务计数）、播放次数规则（阈值一次性、暂停/seek/倍速不计数、会话领养防丢失/防重复、失败放弃会话）、扫描合并（幂等、MISSING 标记、移动/重命名记录迁移、失败不清库）、进度校验策略、文件夹聚合与排序查询。

## 真实验证记录（API 36 模拟器）

安装 APK → 授权 → 推送真实 MP4 → 应用扫描发现并按 Movies/ 目录归类 → 文件夹页展示时长/大小/分辨率 → 点击进入播放器（ExoPlayer 初始化、解码器工作、无错误）→ 退出返回文件夹页，全程无崩溃。

## 设备测试步骤（真机）

1. `./gradlew installDebug` 或直接安装 debug APK。
2. 首次启动授予"照片和视频"权限（Android 13+ 为视频权限，旧版为存储权限）。
3. 验证：文件夹列表与系统相册目录一致；进入文件夹查看视频信息。
4. 播放一个较长视频约 15 秒 → 退出 → 重新打开：应提示/恢复到上次位置，播放次数 +1。
5. 播放另一个视频到结尾 → 再打开：默认从头播放（可在设置改为"每次询问"）。
6. 快速连续打开 A、B、C 三个视频后逐一退出：各自断点互不干扰。
7. 设置页：切换默认排序/恢复行为/倍速记忆；"清理失效索引"删除已删除文件的索引（不触碰原文件）。
8. 删除一个视频文件 → 等待自动增量扫描或手动重扫：该视频标为不可访问但记录保留；"清理失效索引"后消失。
9. （可选）设置 → 授权目录 → 选择 Download 子目录 → 验证其中的视频被收录。

## 已知限制与兼容性说明

1. **签名**：release 构建未配置签名密钥，交付 debug APK 与未签名 release APK。
2. **设备兼容性**：已在 API 36 模拟器完成端到端验证；未在真实品牌设备（含华为等兼容设备）上验证。标准 Android APK 在华为设备上预期可用，但 HarmonyOS NEXT 不运行标准 Android APK；不宣称对所有机型兼容。
3. **倍速上限**：3.0x 等高速档在部分低端设备解码器上可能丢帧或无声，属设备能力限制。
4. **SAF 目录**：扫描为 DocumentFile 遍历（深度 ≤ 5），超大目录首次扫描较慢；SAF 视频无时长/分辨率元数据（播放正常）；授权失效后需在设置中重新添加。
5. **DRM/特殊编码**：受 Widevine 或非常规编码保护的视频依赖设备解码能力，可能播放失败（会明确提示且保存最后位置）。
6. **后台播放**：切后台自动暂停（无后台音频服务）；进度已保存。
7. **数据安全**：数据库随应用卸载删除（未启用 Android 备份的跨设备恢复）；原始视频文件永不修改。
