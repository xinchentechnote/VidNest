# VidNest 数据模型设计

数据库：Room 2.8.5，`vidnest.db`，版本 1。所有时间戳：`dateModified` 为 MediaStore 的 epoch 秒；其余（`lastScannedAt`、`lastPlayedAt`、`updatedAt`、`startedAt`）为 epoch 毫秒。

## 1. videos（视频索引表）

| 字段 | 类型 | 约束 | 设计理由 |
|---|---|---|---|
| id | Long | PK, autoGenerate | 内部主键，与播放记录关联；与 MediaStore id 解耦，MediaStore id 可能因文件移动而变化 |
| volumeName | String | notNull | 存储卷（`external_primary` 或 `saf:<treeId>`），区分主存储与 SAF 授权目录 |
| mediaStoreId | Long | notNull | MediaStore `_ID`，主存储视频的身份标识；SAF 视频固定 -1 |
| contentUri | String | notNull, **UNIQUE 索引** | 播放用内容 URI。全局唯一：同一文件同一 URI；重复扫描按此 upsert，天然幂等防重复 |
| relativePath | String | notNull, **索引** | 归一化目录路径（如 `DCIM/Camera`）。文件夹列表与归类查询按此分组；MediaStore 端 `RELATIVE_PATH`（API 29+）或由 `DATA` 推导（API 24-28）；SAF 端为 tree 内相对路径 |
| displayName | String | notNull | 文件名。同名不同目录的视频因 relativePath 不同而天然区分 |
| sizeBytes | Long | notNull | 文件大小；列表展示 + 移动/重命名识别依据 |
| durationMs | Long | notNull | 时长（MediaStore 元数据）；展示 + 排序 + 计数阈值 |
| width / height | Int | notNull | 分辨率，可获取时展示（未知为 0） |
| dateModified | Long | notNull | MediaStore `DATE_MODIFIED`（秒）；排序 + 变更检测 |
| lastScannedAt | Long | notNull | 上次确认存在的时间；清理失效索引时参考 |
| availabilityStatus | Int | notNull | 0=AVAILABLE，1=MISSING。文件消失只改状态不删行，保护播放记录；用户手动"清理失效索引"才物理删除 |

索引：`UNIQUE(contentUri)`（幂等 upsert 的冲突目标）；`INDEX(relativePath)`（文件夹查询）；`INDEX(availabilityStatus)`（筛选失效项）。

## 2. playback_records（播放统计与断点表，每视频一行）

| 字段 | 类型 | 约束 | 设计理由 |
|---|---|---|---|
| videoId | Long | **PK**，FK→videos.id CASCADE | 一对一：主键即视频 id，避免视频 id 与记录错配；视频行被用户清理时级联删除 |
| positionMs | Long | notNull, default 0 | 断点位置 |
| durationMs | Long | notNull, default 0 | 保存时刻的媒体时长快照，用于校验进度有效性（时长变化检测） |
| playCount | Int | notNull, default 0 | 播放次数 |
| lastPlayedAt | Long | nullable | 最近播放时间（epoch ms） |
| completed | Boolean | notNull, default 0 | 已看完标记；决定下次播放从头开始 |
| lastPlaybackSpeed | Float | nullable | 信息展示用（上次观看倍速）；播放器实际恢复值来自全局设置 |
| updatedAt | Long | notNull | 写入时间 |

设计要点：视频索引与播放记录生命周期解耦——视频 MISSING 时记录保留，重新出现后继续使用；只有用户显式清理失效索引才级联删除。断点与计数在**同一事务**中更新（见 playback-and-progress.md §4）。

## 3. playback_sessions（播放会话表，崩溃安全计数）

| 字段 | 类型 | 约束 | 设计理由 |
|---|---|---|---|
| sessionId | String | PK（UUID） | 会话标识，跨进程死亡持久化 |
| videoId | Long | notNull, FK→videos.id CASCADE, **索引(videoId, counted)** | 归属视频；索引用于"领养未计数会话"查询 |
| accumulatedMs | Long | notNull, default 0 | 累计实际播放时长（暂停不计入），达到阈值即计数 |
| counted | Boolean | notNull, default 0 | 是否已计入播放次数，保证每会话至多计一次 |
| startedAt | Long | notNull | 会话开始时间 |
| updatedAt | Long | notNull | 废弃清理与领养窗口判断 |

延迟确认机制的崩溃安全性：

- `accumulatedMs` 与断点在**同一事务**落库（每 5s），进程被杀最多丢最近 5s 计时，不丢不重。
- 重新打开同一视频时**领养** 24h 内未计数的旧会话继续累计（不丢失、不重复）；超过 24h 的未计数会话在启动时清理（视为废弃播放）。
- 已计数会话在退出播放器时删除（数据已固化进 record）。

## 4. Room 迁移策略

- v1 首发。`exportSchema = true`，schema JSON 入库（`app/schemas`），为将来迁移提供基线。
- 破坏性字段变更出现前，新增字段一律带 default 值 + `ALTER TABLE ADD COLUMN` 迁移；迁移测试用 `room-testing` 的 `MigrationTestHelper`。
- 不使用 `fallbackToDestructiveMigration()`：禁止因升级清空用户播放历史。

## 5. DAO 职责

- `VideoDao`：`upsertAll`（按 contentUri 冲突更新）、按目录/全部/搜索观察流、目录聚合查询（GROUP BY relativePath 返回目录名+数量）、标记/恢复可用状态、按 id/uri 查询。
- `PlaybackRecordDao`：按视频观察/读取、**定向字段更新**（`updatePosition`、`markCompleted`、`incrementPlayCount`、`markPlayed`——不用全行 upsert，避免过期对象覆盖其他字段）、按最近播放排序查询。
- `PlaybackSessionDao`：插入/领养/更新累计/标记已计数/删除。

关键约束：**记录更新只允许定向 UPDATE 或"读取-修改-写入"包在 `@Transaction` 中**，禁止用 UI 层构造的完整对象直接 upsert 覆盖 `playCount` 等字段。
