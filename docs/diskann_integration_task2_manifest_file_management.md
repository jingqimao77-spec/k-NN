# DiskANN Step 2：Manifest 方案与 Lucene 文件管理（细化）

本任务聚焦 DiskANN 多文件的可追踪与可恢复：通过 manifest + Lucene Directory 读写，实现文件闭环与 compound 适配。

## 目标
- 形成可被 Lucene 追踪的 DiskANN 多文件集合（`.diskann_*`）。
- 使用 manifest 作为唯一文件清单来源，避免遗漏。
- compound 开启时生成 `*.c` 文件，并保证读取路径正确。

## 关键实现点
### 1) Manifest v1 结构（`.knowhere`）
- 文件名：`<engine_file>.knowhere`（compound 后为 `.knowherec`）。
- 最小 schema：
  - `version`：固定 `1`
  - `engine`：`"knowhere"`
  - `method`：`"diskann"`
  - `index_prefix`：`<engine_file_basename>.diskann`
  - `files`：数组
    - `name`：DiskANN 文件名（不带 `c`）
    - `required`：是否必需
    - `size_bytes`：可选
- 行为约束：
  - **严格校验**：必需文件缺失直接失败。
  - **未知文件**：告警但仍写入 manifest。

### 2) OpenSearchFileManager（JNI C++）
实现 `AddFile/LoadFile/IsExisted/RemoveFile`，以 Lucene Directory 为主存：
- `AddFile`：本地 DiskANN 文件写入 Lucene Directory（通过 `IndexOutputWithBuffer`）。
- `LoadFile`：从 Lucene Directory 读回本地 `data_path`（通过 `IndexInputWithBuffer`）。
- `IsExisted` / `RemoveFile`：基于 Directory 文件名判断/删除。
- Build 结束生成 manifest（严格校验逻辑在 JNI 层执行）。
- **遗留**：manifest 生成需接入 Step 3 的 JNI/knowhere build 路径（当前仅完成 FileManager 实现）。

### 3) Compound 适配
`KNN80CompoundFormat` 增加 DiskANN 多文件处理：
- `.knowhere` 仍按引擎扩展规则 copy 为 `.knowherec`。
- 解析 manifest，枚举 `.diskann_*` 并 copy 为 `.diskann_*c`。
- 从 `SegmentInfo.files()` 移除原 `.diskann_*`。

### 4) 引擎文件枚举
`KNNCodecUtil.getEngineFiles` 在 knowhere 分支：
- 通过 manifest 获取文件清单。
- compound 开启时返回 `.diskann_*c`，非 compound 返回 `.diskann_*`。
- 其他引擎保持原有逻辑。

## 测试计划
- UT：`KNNCodecUtilTests`
  - **non‑compound** 场景：不追加 `c`。
  - knowhere manifest 解析：返回 `.diskann_*` / `.diskann_*c`。
- UT：`KNN80CompoundFormatTests`
  - knowhere manifest 触发 `.diskann_* → .diskann_*c` 的 copy 与删除。
- JNI UT：`OpenSearchFileManager` 的 Add/Load/Exist/Remove（如当前 JNI UT 不启用，则先放入测试文件夹等待后续启用）。

## 遗留项（集成验证）
Build → Load → Search 的端到端验证依赖 Step 3/4（JNI & knowhere 接入 + mmap 支持），
本步骤仅记录遗留，待所有步骤完成后统一验证。
