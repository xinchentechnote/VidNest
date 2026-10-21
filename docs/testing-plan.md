# VidNest 测试计划

## 1. 测试矩阵

| 层 | 框架 | 环境 |
|---|---|---|
| 纯逻辑（policy：计数阈值、进度校验、状态映射、排序） | JUnit4 | 本地 JVM |
| 数据库（DAO、事务、迁移基线） | JUnit4 + Robolectric 4.17 + Room in-memory | 本地 JVM（Robolectric 提供 Android 框架） |
| Repository / 扫描合并逻辑 | JUnit4 + Robolectric + fake VideoSource | 本地 JVM |
| MediaStore 游标行映射 | 纯函数单测（构造行数据） | 本地 JVM |
| 真机行为（权限弹窗、真实解码、SAF、ContentObserver） | androidTest + 手动设备测试步骤 | 设备/模拟器 |

不用纯 Mock 冒充真实设备验证：涉及权限、文件系统、解码器的行为以 androidTest + 手动步骤覆盖，README 记录步骤。

## 2. 断点隔离测试（PlayRecordDaoTest / ProgressWriterTest）

- A、B、C 三视频分别写入不同进度 → 三行记录各自正确。
- 分别重新打开（读取）→ 恢复位置正确。
- 模拟快速切换乱序写入（并发提交 100 个交错写请求）→ ProgressWriter 串行执行后各视频位置为最后一次请求值，无串号。
- markCompleted 后 positionMs=0、completed=true；再次读取从头。
- 记录的 durationMs 与新时长差异校验（ProgressPolicyTest：clamp、无效进度归 0、时长变化）。

## 3. 播放次数测试（PlayCountPolicyTest / SessionRepositoryTest）

- 阈值：10s 视频阈值 3.33s→取 3333ms；30min 视频阈值 10s；未知时长 10s。
- 一个会话只计一次：累计两次达到阈值只 +1。
- 暂停/恢复/seek/倍速不产生时长增量（增量由 coordinator 按播放态过滤，单测覆盖）。
- 领养：未计数旧会话继续累计；>24h 未计数会话被清理。
- 不同视频会话互不影响。
- 崩溃安全：accumulatedMs 与断点同事务（Room @Transaction 验证中途失败回滚）。

## 4. 扫描测试（ScannerMergeTest / VideoDaoTest）

- 同一批行重复 upsert → 行数不变（幂等）。
- 新增行入库；消失行标 MISSING 不删除；MISSING 行播放记录保留。
- 同名不同目录 → relativePath 不同，两行独立。
- 移动/重命名：新 mediaStoreId + 同名同大小唯一候选 → 记录迁移；多候选 → 不迁移。
- 部分失败：单批抛异常 → 已入库批次保留，报告失败。
- 文件夹聚合查询：目录名 + 数量正确；空目录不出现。
- 排序与搜索查询正确。

## 5. 播放器测试

- PlayerController 单测（Robolectric）：play/pause/seek/setSpeed 状态流转；位置 tick；错误监听置 error 态。
- 倍速切换后位置不变（controller 内部快照校验）。
- ENDED → completed 置位路径（结合 policy）。
- （设备）androidTest 冒烟：Application 启动、空库文件夹页空态、导航到播放器页不崩溃；真机手动步骤见 README。

## 6. 覆盖边界场景 ↔ 测试映射（摘要）

| prompt 边界场景 | 覆盖 |
|---|---|
| 首次启动无视频 | 空态 UI + LibraryViewModelTest |
| 拒绝/撤销权限 | 权限态 UI；扫描器 SecurityException 测试 |
| 大量视频 | 分批事务测试；查询走索引 |
| 同名视频 | ScannerMergeTest |
| 移动/重命名/删除 | ScannerMergeTest |
| URI 失效 | 播放 IO 错误标记 MISSING（controller 测试） |
| 进程被杀 | 会话领养测试 + 持久化验证 |
| 连续切换视频 | ProgressWriter 串行测试 |
| 快速暂停/拖动 | policy 单测 |
| 旋转重建 | ViewModel 存活设计 + configChanges；计数会话复用测试 |
| 结束后再开 | markCompleted 测试 |
| 初始化失败/格式不支持 | 错误态 UI + 重试 |
| 重复扫描 | 幂等测试 |
| 损坏文件/异常元数据 | duration=0/负数容错（policy 单测） |
| 旧版本无效记录 | 校验规则（ProgressPolicyTest） |
| 设备重启后重开 | 持久化天然覆盖（Room 落盘） |

## 7. 执行

- `./gradlew testDebugUnitTest`：全部本地测试（CI 可跑）。
- `./gradlew assembleDebugAndroidTest`：编译设备测试（本环境无设备时至少保证可编译）。
- 有设备/模拟器时：`./gradlew connectedDebugAndroidTest` + README 手动步骤。
