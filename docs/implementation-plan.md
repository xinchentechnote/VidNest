# VidNest 实施计划与进度

> 本文件随开发推进持续更新，只记录已验证的事实。状态：⬜ 未开始 / 🟨 进行中 / ✅ 完成

## 阶段 1：需求分析与技术设计 — ✅

- [x] 检查工作区（仅有 doc/prompt.md，全新项目）
- [x] docs/requirements.md
- [x] docs/architecture.md
- [x] docs/data-model.md
- [x] docs/permissions-and-media-scanning.md
- [x] docs/playback-and-progress.md
- [x] docs/testing-plan.md
- [x] 版本选型核实（AGP 9.4.1 / 内置 Kotlin 2.4.20 / KSP 2.3.12 / Compose BOM 2026.09.00 / Media3 1.11.1 / Room 2.8.5，均经 Maven 元数据与官方文档核实）

## 阶段 2：工程骨架与基础数据层 — ✅

- [x] Gradle 工程（wrapper 9.8.2、版本目录；AGP 9.4.1 + 独立 KGP 2.3.21 + KSP 2.3.12 + Compose）
- [x] 首次编译通过（`assembleDebug`，compileSdk 37 / target 36 / minSdk 24）
- [x] Room 实体 / DAO / 数据库（videos、playback_records、playback_sessions，schema 已导出）
- [x] Repository（VideoRepository / PlaybackRecordRepository / PlaybackSessionRepository）
- [x] ServiceLocator + 基础导航 + 应用骨架 + 权限门控 UI
- [x] domain/policy 纯逻辑（PlayCountPolicy、ProgressPolicy、WatchStatus）
- [x] 单元测试 32 个全部通过（policy/formatters/WatchStatus/VideoDao-Robolectric）

构建链踩坑记录（详见 docs/architecture.md §构建链决策）：AGP 9 内置 Kotlin 与 KSP2+Room 存在
MissingType 兼容问题，已按官方文档退回独立 KGP + 旧版 DSL；@Entity 类体内禁用 companion/成员函数
（ksp#2026）；排查 KSP 问题必须 --no-build-cache（cache 会产生 FROM-CACHE 假成功）。

## 阶段 3：视频库与文件夹浏览 — ✅

- [x] MediaStoreVideoSource（API 24-28 用 DATA 推导目录；29+ 用 RELATIVE_PATH；取不到目录归入 unknown）
- [x] DeviceVideoScanner：全量扫描、分批事务 upsert、MISSING 标记（数据源上报 coveredVolumes 区分"扫到 0 条"与"扫描失败"）、移动/重命名识别（唯一同名同大小才迁移记录）、幂等
- [x] MediaStoreObserver（ContentObserver + 3s 防抖）+ ScanManager（应用级作用域、单飞、状态机、冷启动自动扫一次）
- [x] VideoRepository 查询：目录聚合 / 目录内列表 / 全库搜索（LIKE 转义）/ 六种排序（RawQuery）
- [x] 库首页（扫描进度横幅/摘要/错误、文件夹网格、全部视频入口）/ 文件夹页（排序菜单）/ 全部视频页（防抖搜索 + 状态筛选）
- [x] 扫描合并与 DAO 测试（ScannerMergeTest 6 项 + MediaStoreRowMappingTest 5 项）

## 阶段 4：播放器与逐视频断点 — ✅

- [x] PlayerController（ExoPlayer 封装、状态 Flow、500ms tick + seek 防护媒体增量、错误处理、IO 错误标记 MISSING）
- [x] 播放器 UI：播放/暂停、进度条 seek、快进快退 10s、时间显示、9 档倍速面板、错误重试、加载态、沉浸式 + 常亮、断点恢复提示（≥15s）、视频不可访问态
- [x] ProgressWriter 串行写入器（limitedParallelism(1)，快照请求，与调用方生命周期解耦）
- [x] PlaybackCoordinator：5s 周期保存、暂停/切换/退出/错误立即保存、ENDED 置 completed 归零、sanitize 校验
- [x] 断点恢复：ProgressPolicy.resolveResumePosition（completed→0、无效进度→0、时长变化校验）
- [x] 测试：ProgressWriterTest（快速切换 ABC 串号/并发交错/同事务计数）+ PlaybackCoordinatorTest（保存时机 7 项），共 55 个本地测试通过

## 阶段 5：播放次数与观看统计 — ⬜

- [ ] playback_sessions 表 + 会话领养/清理
- [ ] 实际播放时长累计（暂停不计、seek 跳变防护、倍速按媒体时间）
- [ ] 计数落库（与断点同事务）、每会话至多一次
- [ ] WatchStatus 全局统一 + 列表徽标
- [ ] 视频详情页（播放次数、上次时间/位置、状态）
- [ ] 计数 policy / 会话仓库测试

## 阶段 6：完善 UI 与异常处理 — ⬜

- [ ] 设置页（重扫、权限状态、默认排序、断点恢复行为、结束行为、计数规则说明、清理失效索引、版本、SAF 文件夹管理）
- [ ] SAF 授权目录扫描（持久化授权、失效提示、移除）
- [ ] DataStore 设置（排序默认、恢复行为、记住倍速）
- [ ] 全部页面加载/空/错误/权限态统一；中文文案
- [ ] Material 3 主题与通用组件

## 阶段 7：验证与交付 — ⬜

- [ ] testDebugUnitTest 全部通过
- [ ] assembleDebug 成功；评估 release 构建（无签名密钥时如实说明）
- [ ] androidTest 编译（设备测试需真机，步骤入 README）
- [ ] README.md（构建、安装、功能清单、设备测试步骤、已知限制）
- [ ] 权限清单核查（仅媒体读权限，无多余权限）
