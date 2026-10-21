# VidNest 权限与媒体扫描设计

## 1. 存储权限策略（按 Android 版本）

| API | 权限 | 说明 |
|---|---|---|
| 33+（Android 13+） | `READ_MEDIA_VIDEO`（运行时） | 细粒度媒体权限，只需视频读权限 |
| 29–32 | `READ_EXTERNAL_STORAGE`（运行时） | 系统自动按媒体类型过滤（Android 10 Scoped Storage） |
| 24–28 | `READ_EXTERNAL_STORAGE`（运行时） | 传统读权限 |

- 使用的 API：`ActivityCompat.requestPermissions` + `ContextCompat.checkSelfPermission`；权限声明中同时声明旧版与新版权限并加 `android:maxSdkVersion="32"` 于 `READ_EXTERNAL_STORAGE`。
- **不申请** `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`。
- 授权流程：启动检测权限 → 未授权展示引导页（说明用途）→ 系统弹窗 → 授权后自动扫描；拒绝后展示空态 + "去授权"按钮，可再次触发；权限被系统撤销（`onResume` 重新检测 / ContentObserver 收到访问异常）→ 回到引导态，**不崩溃、不清数据库**。
- 设备兼容性说明：华为等 Android 兼容设备运行标准 Android APK 时，MediaStore 与权限机制与 AOSP 一致；但 HarmonyOS NEXT 不运行标准 Android APK。在真实设备验证前不宣称兼容性已保证（README 记录验证步骤）。

## 2. 视频扫描

### 2.1 数据源

`VideoSource` 接口有两个实现：

1. **MediaStoreVideoSource**（主）：查询 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`（各 volume），projection：`_ID, DISPLAY_NAME, RELATIVE_PATH(API29+), DATA, SIZE, DURATION, WIDTH, HEIGHT, DATE_MODIFIED, VOLUME_NAME(API29+)`。
   - API 29+：`relativePath` 直接取 `RELATIVE_PATH`（去掉尾部 `/`）。
   - API 24–28：无 `RELATIVE_PATH`，从 `DATA` 绝对路径剥离存储根（`/storage/emulated/0/` 等 `Environment.isExternalStorageManager` 根列表）得到相对目录；失败时归入 `(unknown)`，不伪造路径。
   - `contentUri = ContentUris.withAppendedId(mediaUri, id)`。
2. **SafVideoSource**（可选增强）：用户通过 `ACTION_OPEN_DOCUMENT_TREE` 授权系统媒体库覆盖不到的目录（如 Download 子目录）。
   - `takePersistableUriPermission` 持久化授权；treeUri 存 DataStore。
   - DocumentFile 递归遍历（深度 ≤ 5，跳过隐藏目录与非视频 mime），视频项：`mediaStoreId=-1`、`volumeName="saf"`、`relativePath="saf/<treeName>/<相对目录>"`、`contentUri=DocumentsContract URI`、身份=URI 本身。
   - 授权失效检测：扫描前 `DocumentFile.canRead()`；失效 → 提示重新授权，该项视频标 MISSING，不自动删除。
   - 用户在设置中移除授权目录 → 删除该 volume 的视频行与播放记录（显式用户操作，不涉及原始文件）。

### 2.2 扫描时机

| 时机 | 方式 |
|---|---|
| 首次启动授权后 | 全量扫描 |
| 应用冷启动（已有权限） | 全量扫描一次（MediaStore 查询为索引查询，成本可接受），后续页面切换只读 DB |
| 手动"重新扫描"（设置页/下拉） | 全量扫描 |
| ContentObserver 变更（增/删/改视频） | debounce 3s 后全量重扫，绝不在每次进页面时扫描 |
| 进后台/存储不可用 | 不触发 |

### 2.3 扫描与既有数据的合并策略（关键）

单次扫描产出"当前 MediaStore 全集"，与 DB 合并：

1. **upsert**：DB 已有同 `contentUri` → 更新元数据字段（size/duration/dateModified/分辨率/lastScannedAt），**保留 id 与播放记录**；无 → 插入。
2. **标记缺失**：DB 中本次扫描未出现的行 → `availabilityStatus = MISSING`。**不删除行，不删除播放记录**。
3. **移动/重命名识别**：扫描结束后，对本批"新出现的 mediaStoreId"行，在 MISSING 行中找唯一满足「displayName 相同 && sizeBytes 相同」的候选 → 将 playback_records 迁移到新行、删除旧行。候选不唯一则不迁移（宁可丢失关联也不错误套用他人进度）。
4. **分批事务**：每 200 行一个事务 upsert；任一批失败保留已写入数据并向 UI 报告失败数，绝不因部分失败清空库。
5. **权限丢失**：查询抛 `SecurityException` → 本轮扫描中止，报告"权限不可用"，DB 数据原样保留。
6. **幂等**：重复扫描同一批文件，upsert 目标为唯一索引 contentUri，不产生重复行。

### 2.4 状态区分（不伪造、不混淆）

| 状态 | 判定 |
|---|---|
| 扫描未完成 | 扫描流程进行中，UI 显示进度 |
| 无权限 | 未授予运行时权限 |
| 文件已删除 / URI 失效 | availabilityStatus = MISSING（或播放时 IO 错误即时标记） |
| 文件不可访问（权限收窄/存储卸载） | 播放失败回传，标记 MISSING 并提示 |
| SAF 授权失效 | 对应 volume 全部标 MISSING + 设置页提示重新授权 |

扫描器输出 `ScanResult`（新增/更新/标记缺失/失败计数）供 UI 展示进度与结果；扫描全程 IO 线程，UI 只订阅 Flow。
