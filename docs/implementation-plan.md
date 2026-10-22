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

## 阶段 5：播放次数与观看统计 — ✅

- [x] playback_sessions 表 + 24h 会话领养 + 启动清理（counted 与过期未计数）
- [x] 实际播放时长累计：暂停不累计、seek 跳变防护（PlayerController tick 增量）、倍速按媒体时间
- [x] 计数落库：达到 PlayCountPolicy 阈值 → CountReached（playCount+1 与 counted 标记与断点同事务），每会话至多一次
- [x] 进程被杀：SessionTick 周期落库 + 领养继续累计（测试覆盖：不丢失不重复）
- [x] 播放失败：放弃未计数会话（不计数、不再领养）
- [x] 视频详情页：观看状态/播放次数/最近播放/上次位置/完成状态 + 媒体信息 + 播放入口
- [x] 列表项详情入口；WatchStatus 全局统一徽标（阶段3已接）
- [x] 测试：PlaybackSessionRepositoryTest 6 项 + PlayCountFlowTest 7 项（端到端 coordinator→writer→Room）

## 阶段 6：完善 UI 与异常处理 — ✅

- [x] 设置页：重新扫描、权限状态、默认排序、断点恢复行为（总是/询问/从不）、结束行为（从头/询问）、
  记住倍速开关、播放次数规则说明、清理失效索引（确认+结果反馈，不删原始文件）、版本号
- [x] SAF 授权目录：OPEN_DOCUMENT_TREE 添加（takePersistableUriPermission）、失效提示、
  移除（级联清理该 volume 索引）、扫描器按设置动态组装数据源
- [x] DataStore 设置（SettingsRepository）+ 播放器集成：断点恢复三态、已看完询问对话框、记住倍速
- [x] 播放器横竖屏切换按钮（真实生效）；默认排序应用到文件夹页/全部视频页
- [x] 启动时会话清理（counted + 过期未计数）
- [x] 全部页面加载/空/错误/权限态；中文文案；Material 3 动态取色主题

## 阶段 7：验证与交付 — ✅

- [x] testDebugUnitTest 全部通过（69 个测试）
- [x] assembleDebug 成功（app-debug.apk 23.9MB）；assembleRelease 构建成功但为未签名 APK（无签名密钥，如实说明）
- [x] assembleDebugAndroidTest 编译通过；connectedDebugAndroidTest 在 API 36 模拟器执行通过
- [x] lintDebug：0 错误，29 个警告（不阻塞）
- [x] 端到端真机冒烟（API 36 模拟器）：安装 → 授权 → 推送真实 MP4 → 扫描归类 Movies/ →
      文件夹页展示 → 播放器播放（ExoPlayer 初始化、解码器工作、无错误）→ 退出返回文件夹，全程无崩溃
- [x] README.md（构建、安装、签名说明、功能清单、测试、真机步骤、已知限制）
- [x] 权限清单核查：仅 READ_MEDIA_VIDEO + READ_EXTERNAL_STORAGE(maxSdk=32)

## 追加迭代（真机反馈，v1.0.1）

- [x] 后台扫描：ScanService 前台服务（dataSync）统一承接所有扫描触发，退后台继续；进度通知
- [x] 层级文件夹浏览：库首页只显示根目录，SQL 按路径段分组聚合（计数含全部子层级）
- [x] 列表项明确显示：时长（未知显示 --:--）、大小、分辨率、已播放 N 次、观看状态、上次位置

## 追加迭代 2（v1.0.2）

- [x] 播放器双击暂停/继续；播放页播放列表（同文件夹 ≥2 视频时显示按钮，切换防串号）
- [x] 列表项统计标签更明确（已播放 N 次 / 已看 mm:ss）

## 追加迭代 3（v1.0.3）

- [x] 全部视频页观看状态 Tab（全部/未播放/在看/已看完 + 实时数量角标）
- [x] 长按视频菜单：播放 / 查看详情 / 打开所在文件夹
- [x] 修复关键显示缺陷：联表 POJO 的 @Embedded(prefix) 与 @ColumnInfo 命名冲突导致
      播放记录映射恒为 null；新增联表映射回归测试

## 追加迭代 4（v1.0.4，信息架构重构）

- [x] 底部 Tab 导航：全部（默认首页，唯一有搜索）/ 文件夹 / 播放历史 / 播放统计；
      权限门控全局拦截
- [x] 播放统计页：按天/周/月 + 按视频；媒体时长（倍速后）vs 实际时长对比 +
      平均倍速/节省时长 —— Schema v2（playback_sessions.wallMs，迁移 1→2 + 迁移测试）
- [x] 播放历史页：按最近播放倒序 + 今天/昨天/日期分组
- [x] 修复：①底部 Tab 路由不一致导致点击崩溃；②counted 会话不再落库导致统计恒为 0
- [x] 列表紧凑化（后按反馈适度放松）

## 追加迭代 5（v1.0.5）

- [x] 倍速改为列表面板，内容由设置自定义（全集 9 档多选，默认 1.0/1.5/2.0/2.5/3.0）
- [x] 修复倍速列表存取 bug（encode 带 x 后缀导致解析回落默认）+ SpeedsTest
- [x] 子文件夹显示统一为紧凑单行；列表适度放松（行距/字号/缩略图）
- [x] 快进/快退按钮半透明化；修复设置入口回归（移至「全部」页顶栏）
- [x] 端到端验证：倍速对话框/DataStore 写入/播放面板列表/子目录紧凑行/控制层透明度
      （模拟器实测 + 截图）；测试 80 个全部通过
- [x] 修复平均倍速统计 bug：①墙钟累计被 counted 门控（计数后停止）；
      ②tick 粒度整除截断漂移；③ENDED 丢尾。改为 Double 累计 + 按倍速折算推导
      （wall = mediaDelta/倍速，墙钟积分的精确近似）+ ENDED 补尾；
      新增 3 项回归测试（1x 恒等、3x 折算、ENDED 补尾），共 83 个测试通过
