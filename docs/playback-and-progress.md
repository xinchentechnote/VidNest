# VidNest 播放、断点与计数设计

本文是核心行为规范，实现必须与本文一致。

## 1. 播放器（Media3 ExoPlayer）

- `PlayerController` 封装 ExoPlayer（主线程构造），暴露状态 Flow：`isPlaying`、`positionMs`（500ms tick）、`durationMs`、`speed`、`isLoading`、`error`、`currentVideoId`。
- 控制 API：play/pause、seekTo、seekBack/Forward（10s）、setSpeed、stop-and-release。
- 倍速：`player.setPlaybackSpeed(speed)`，9 档 0.5~3.0；切换不重置位置；默认 1.0x；全局记住上次倍速（DataStore，默认开启"记住倍速"）。
- 异常倍速处理：ExoPlayer 对超出解码能力的速度可能花屏/无声，属设备能力问题；UI 提供 9 档以内不越界，错误监听兜底提示。

## 2. 断点（逐视频进度）

### 2.1 保存时机

| 时机 | 动作 |
|---|---|
| 播放中定时 | 每 5s 持久化一次（UI tick 500ms 仅更新内存） |
| 暂停 | 立即保存 |
| 切换视频 | 先 flush 当前视频（NonCancellable 投递串行队列），再装载新视频 |
| 退出播放器 / onStop | 保存 |
| 播放结束（STATE_ENDED） | 见 §3 |
| 播放错误 | 保存最后已知位置 |

### 2.2 恢复规则

1. 打开视频 → 读 `playback_records`。无记录 → 从 0 开始。
2. `completed = true` → 按设置：默认**从头播放**；设置为"询问"时弹窗让用户选择。
3. 有未看完断点 → 恢复 `positionMs`，并提示"已恢复到上次位置 12:30"（≥15s 才提示）。
4. 校验：`positionMs < 0` → 0；`positionMs > durationMs + 2000` → 0（无效进度）；记录的 `durationMs` 与当前媒体时长差异 > 5% 时，`positionMs` 超出新时长则归 0，否则照常恢复。
5. 用户拖动进度条始终允许，恢复只是初始位置。
6. 不可 seek 的媒体（直播流/未知时长）：跳过断点恢复与保存（进度无意义），计数照常按实际播放时长。

### 2.3 事务一致性与防串号

- 断点与计数会话**同一事务**落库（`@Transaction` 中定向 UPDATE record + session）。
- 所有写经 `ProgressWriter`（`Dispatchers.IO.limitedParallelism(1)`）串行执行；写请求为不可变快照（videoId + 位置 + 会话累计），与 UI 协程生命周期无关（`NonCancellable` 入队）。
- 快速切换 A/B/C：每次切换先 flush 前一个视频，写入按入队顺序串行执行，不同 videoId 各自 upsert，物理上不可能串号。

## 3. 播放结束规则

- `STATE_ENDED`：置 `completed = true`、`positionMs = 0`（下次从头）、`playback_records.lastPlaybackSpeed` 记录当前倍速；会话按 §4 正常收尾（计入次数）。
- 不得把 `positionMs` 保存为视频末尾（否则恢复即结束）。
- 播放结束后行为设置：`从头开始`（默认）/ `每次询问`（弹窗选择继续观看位置或从头）。

## 4. 播放次数规则（精确定义）

### 4.1 有效播放会话

- 进入 `STATE_READY && playWhenReady` 后开始**播放会话**（`playback_sessions` 行，UUID 主键，持久化）。
- 会话累计的是**实际播放时长** `accumulatedMs`：仅播放态增长；暂停、seek、倍速变化不触发计数，只影响累计速度（倍速下按媒体时间累计）。
- **计数阈值**：`min(10_000ms, durationMs / 3)`；`durationMs` 未知/≤0 时取 10_000ms；对 <30s 的短视频，3s 钟形曲线由 `/3` 自然覆盖（例：15s 视频需播放 5s）。
- `accumulatedMs` 首次达到阈值：`playCount += 1`、`counted = true`、`lastPlayedAt = now`（与断点同事务）。此后本会话不再累计计数。

### 4.2 各场景计数行为

| 场景 | 行为 |
|---|---|
| 暂停/恢复/拖动/倍速 | 不计数，累计继续（暂停期间不累计时长） |
| 播放器重建（旋转等配置变化） | ViewModel 存活 → 同一 sessionId，天然不重复 |
| Activity 被系统重建 | 同上（ExoPlayer 在 ViewModel 中） |
| 进程被杀后重开同一视频 | 领养 24h 内未计数会话继续累计（不丢失不重复） |
| 播放失败/打开即失败 | 会话不累计；退出时删除未计数会话，不计数 |
| 已看完视频重播 | 新会话，达到阈值再次计数（一次观看 = 一次） |
| 播放 < 阈值即退出 | 会话保留（24h 内可续），不计入次数 |

### 4.3 为什么这样设计

- 阈值防误触（点开即走不计次）。
- 会话持久化 + 领养机制保证进程死亡既不丢失已累计时间也不重复计数（每会话 `counted` 至多一次）。
- 计数与播放器生命周期解耦：`PlaybackCoordinator` 只消费"实际播放时长增量"输入，来源死亡不影响已落库数据。

## 5. 观看状态规则（全局统一）

唯一实现：`domain/model/WatchStatus.from(record)`，所有页面共用：

| 状态 | 判定 |
|---|---|
| 未播放 | 无记录，或 `playCount == 0 && positionMs == 0` |
| 已看完 | `completed == true` |
| 正在观看 | 其余情况（`positionMs > 0` 或 `playCount > 0`） |

## 6. 列表展示

- 列表项：名称、时长、大小、分辨率（>0 时）、播放次数、观看状态徽标（未播放/在看 x%、已看完）、上次位置。
- 排序：名称 / 修改时间 / 大小 / 时长 / 最近播放 / 播放次数；默认名称，设置页可改默认。
- 搜索：`displayName LIKE '%'||query||'%'`，全库范围。
