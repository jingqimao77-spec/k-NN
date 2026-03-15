# DiskANN 集成完整执行计划（含并行与依赖）

> 目标：以 `engine: knowhere` 为主线，将 DiskANN 接入 k‑NN 插件，采用 Manifest 多文件管理 + Compound 打包，支持 mmap 访问模式并补齐 knowhere UT。
> 说明：此计划用于并行拆解任务。所有阶段均以可验证结果为验收标准。

---

## 0. 冻结接口与命名（阻断后续）

**内容**
- engine/method 命名：`engine = knowhere`，`method = diskann`
- 引擎主文件扩展名：`KNNEngine.getExtension() = ".knowhere"`（manifest 文件）
- DiskANN 文件命名规则（由 `index_prefix` 推导）
  - `engine_file = <segment>_<version>_<field>.knowhere`
  - `engine_file_basename = <segment>_<version>_<field>`
  - `index_prefix = <engine_file_basename>.diskann`
  - 实际文件名由 DiskANN utils 生成，例如：`index_prefix + "_pq_pivots.bin"`
  - 文件整体形态为 `.diskann_*`（例：`_0_165_my_field.diskann_pq_pivots.bin`）
- Compound 规则冻结
  - compound 启用时：`KNN80CompoundFormat` 对 `.diskann_*` 进行 `copyFrom`，生成 `.diskann_*c`
  - compound 关闭时：保持 `.diskann_*` 原始文件名
- 参数映射表冻结（build/query）
- manifest 结构冻结（字段、必需文件清单）

**参数映射表**
- Build（index-time, method parameters）
  - `max_degree` (int, default 48, range 1..2048)
  - `search_list_size` (int, default 128 if unset, range >=1)
  - `pq_code_budget_gb_ratio` (float, default 0)
  - `build_dram_budget_gb` (float, default 0)
  - `disk_pq_dims` (int, default 0)
  - `accelerate_build` (bool, default false)
  - `num_build_thread` (int, optional, range 1..hardware_concurrency)
  - `search_cache_budget_gb_ratio` (float, default 0)
  - `warm_up` (bool, default false)
  - `use_bfs_cache` (bool, default false)
- Query（search-time, method_parameters）
  - `search_list` (int, maps to `search_list_size` in knowhere)
  - `beamwidth` (int, default 8, range 1..128)
- 暂不对外暴露
  - `pq_code_budget_gb`, `search_cache_budget_gb`（仅保留 ratio）
  - `min_k`, `max_k`, `filter_threshold`

**manifest 格式（JSON `.knowhere`，v1）**
- 文件名：`<engine_file>.knowhere`
- Schema（最小必需字段）
  - `version`: int（固定 `1`）
  - `engine`: `"knowhere"`
  - `method`: `"diskann"`
  - `index_prefix`: string（`<engine_file_basename>.diskann`）
  - `files`: array of objects
    - `name`: string（DiskANN 实际文件名）
    - `required`: bool
    - `size_bytes`: long（可选）
- 必需/可选文件判定规则
  - 必需文件：来自 `GetNecessaryFilenames`
  - `*_disk.index.max_base_norm.bin` 在 IP/COSINE 时必需
  - `*_sample_data.bin` 在 warmup 或 cache 场景必需
  - 可选文件：来自 `GetOptionalFilenames`
- 行为约束
  - manifest 缺必需文件直接失败
  - 未知文件记录告警但纳入 manifest

**命名示例**
- `engine_file`：`_0_165_my_field.knowhere`
- `index_prefix`：`_0_165_my_field.diskann`
- 必需文件示例：`_0_165_my_field.diskann_pq_pivots.bin`、`_0_165_my_field.diskann_disk.index`
- compound 启用后：`_0_165_my_field.diskann_pq_pivots.binc`、`_0_165_my_field.diskann_disk.indexc`

**产出**
- 规范文档（本文件即为接口基线）

**验证**
- 参与成员一致确认接口与命名

---

## 1. Java 层引擎/方法与参数支持

**依赖**：0 完成  
**可并行**：与 2/3/4 并行

**实现**
- 方案细化见 `docs/diskann_integration_task1_java_engine_method_params.md`
- `KNNEngine` 新增 `KNOWHERE`
- 新增 `DiskANNMethod`，构建 `KNNLibraryIndexingContext` 输出 knowhere JSON
- `MethodParameter` 新增 `search_list`, `beamwidth` 等
- `KNNConstants` 增加 `KNOWHERE_NAME` 和扩展名（建议 `.knowhere`）
- `KNNEngine` 扩展名解析与 `getEngineNameFromPath` 支持 `knowhere`

**验证**
- UT: MethodParametersParser/MethodParameter 参数解析与校验
- UT: KNNEngine 解析与扩展名识别

---

## 2. Manifest 方案与 Lucene 文件管理（核心）

**依赖**：0 完成  
**可并行**：与 1/3/4 并行

**实现**
- 设计 manifest 文件格式（JSON 或 KV），至少包含：
  - 必需文件列表
  - 可选文件列表
  - 每个文件的 size（可选 hash）
- JNI C++ 实现 `OpenSearchFileManager`：
  - `AddFile`: 本地 DiskANN 文件写入 Lucene Directory
  - `LoadFile`: 从 Lucene Directory 读回本地 prefix
  - `IsExisted/RemoveFile`
  - 严格校验：必需文件缺失直接失败；未知文件记录告警但写入 manifest
- `KNN80CompoundFormat` 增加 `.diskann_*` 的 copyFrom → `.diskann_*c` 处理
- `KNNCodecUtil.getEngineFiles()`：通过 manifest 枚举文件

**验证**
- SegmentInfo.files() 含 `.diskann_*`
- compound 后存在 `.diskann_*c`，原 `.diskann_*` 删除
- manifest 校验：必需文件缺失失败

---

## 3. JNI & knowhere 接入

**依赖**：0 完成  
**可并行**：与 1/2/4 并行

**实现**
- 新增 JNI so：`opensearchknn_knowhere`
- JNI 接口：build/load/query/free
- `JNIService` routing 增加 `KNNEngine.KNOWHERE`
- build: `data_path` + `index_prefix`
- load: 先按 manifest `LoadFile` 到本地，再 `Deserialize`

**验证**
- JNI UT：Build → Load → Search 正常

---

## 4. knowhere mmap 支持

**依赖**：0 完成  
**可并行**：与 1/2/3 并行（但需在 5 前完成）

**实现**
- 将 `IndexEnum::INDEX_DISKANN` 加入 mmap 支持表
- 实现 DiskANN `DeserializeFromFile` mmap 路径
- 添加 knowhere UT 覆盖 mmap load + search

**验证**
- knowhere UT：enable_mmap = true 路径成功

---

## 5. OpenSearch mmap 配置接入

**依赖**：1 + 4 完成  
**可并行**：与 6 并行

**实现**
- 新增 index setting：`index.knn.diskann.access_mode = native|mmap`
- 设置解析并传递给 JNI/knowhere（`enable_mmap`）

**验证**
- IT: native vs mmap 结果一致

---

## 6. Query/Load 路径打通

**依赖**：1 + 2 + 3 完成  
**可并行**：与 5 并行

**实现**
- Load：按 manifest 下载到本地 prefix
- Search：JNI query 路径
- Filter 不支持时 fallback exact search

**验证**
- ANN search 返回 topK
- filter fallback 正常

---

## 7. Snapshot/Restore + Replica

**依赖**：2 + 6 完成  
**可并行**：可独立测试但依赖前面功能完整

**实现**
- Snapshot/restore 测试：恢复后搜索一致
- Replica 测试：多副本一致性

**验证**
- IT 覆盖 snapshot/restore + replica query

---

## 并行执行建议

**可并行**：1 / 2 / 3 / 4  
**依赖链**：0 → (1,2,3,4) → (5,6) → 7

---

## 假设与默认

- Manifest 方案作为最终多文件策略，不使用固定映射表
- 新增 DiskANN 文件自动纳入 manifest
- 必需文件缺失直接失败，未知文件记录告警但纳入
- Compound 打包与 faiss 保持一致流程
