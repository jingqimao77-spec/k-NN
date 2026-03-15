# DiskANN 集成须知

本文件总结在 milvus、knowhere、k‑NN 代码中与 DiskANN 集成相关的关键事实，供所有参与实现的人共享。

---

## 1. knowhere DiskANN 的核心约束

- **DiskANN 是不可变索引**：不支持 Train‑Add 增量写入路径，Build 后即可查询。
- **Serialize/Deserialize 行为**：
  - `Serialize()` 在 DiskANN 中是 no‑op（不会把索引写入 BinarySet）。
  - `Deserialize()` 依赖 `index_prefix` 和 `FileManager`，从文件加载索引。
  - `DeserializeFromFile()` 目前是 not implemented，需要改造以支持 mmap。
- **文件管理必须通过 FileManager**：
  - Build/Load 过程中会调用 `file_manager_->AddFile()` / `LoadFile()`。
  - OpenSearch 需要实现自己的 `FileManager` 以适配 Lucene Directory。

---

## 2. DiskANN 文件集合（必需/可选）

来源：`/home/developer/mjq/vector/knowhere/src/index/diskann/diskann.cc`

**必需文件（GetNecessaryFilenames）**
- `*_pq_pivots.bin`
- `*_pq_pivots.bin.rearrangement_perm.bin`
- `*_pq_pivots.bin.chunk_offsets.bin`
- `*_pq_pivots.bin.centroid.bin`
- `*_pq_compressed.bin`
- `*_disk.index`
- `*_disk.index.max_base_norm.bin`（IP/COSINE 需要）
- `*_sample_data.bin`（cache/warmup 开启时）

**可选文件（GetOptionalFilenames）**
- `*_disk.index.centroids.bin`
- `*_disk.index.medoids.bin`
- `*_cached_nodes.bin`
- `*_emb_list_offset.bin`

**注意**
- 文件集合可能扩展，避免硬编码映射表遗漏。
- 推荐以 manifest 记录“实际生成的文件列表”。

---

## 3. OpenSearch / Lucene 文件管理特点

来源：`docs/directory_createoutput_callchain.md`

- **Lucene 只追踪 `createOutput` / `copyFrom` 产生的文件**。
- 索引文件必须通过 Lucene `Directory.createOutput()` 写入，才能被 `TrackingDirectoryWrapper` 记录到 `SegmentInfo.files()`。
- **Compound 由 Lucene 决定**：是否使用复合文件由 Lucene 的 `SegmentInfo.getUseCompoundFile()` 决定（通常小段使用 compound，大段不使用）。
- **决策规则不在 k‑NN 插件内**：compound 的启用条件来自 OpenSearch/Lucene core（例如 `IndexWriterConfig`/`noCFSRatio`/`useCompoundFile`），本仓库没有实现该规则。排查时需看 OpenSearch core 或 Lucene 配置。
- `KNN80CompoundFormat` 只在 Lucene 选择 compound 时介入：将引擎文件（如 `.faiss`）从 CFS 列表中移除，**避免 Lucene 对原生格式做 header/footer 校验**，并通过 `copyFrom` 生成独立的 `engine + c` 文件（如 `.faissc`）。
- 当 **不使用 compound** 时，不会生成 `*.c`，引擎文件保持原始扩展名；`KNNCodecUtil.getEngineFiles()` 会根据 `getUseCompoundFile()` 选择是否加 `c`。
- 因此 DiskANN 也应遵循同样流程：**compound 启用时生成 `.diskann_*c`，未启用时保持 `.diskann_*`**。

---

## 3.1 Faiss vs DiskANN（文件层差异）

- **Faiss 是单文件**（`.faiss`），DiskANN 是**多文件集合**，因此 DiskANN 必须有 manifest 来避免遗漏。
- **两者都不能进 Lucene CFS**：引擎文件会被 `KNN80CompoundFormat` 从 CFS 列表移除，再复制成 `engine + c` 文件。
- **compound 关闭时行为一致**：Faiss 保持 `.faiss`，DiskANN 保持 `.diskann_*`；读取时按 `SegmentInfo.getUseCompoundFile()` 判断是否带 `c`。

---

## 4. Manifest 方案建议

- 由 JNI/knowhere build 结束后生成 manifest（JSON/KV）。
- 必需文件缺失直接失败；未知文件记录告警但纳入 manifest。
- Load 时以 manifest 为准逐个 `LoadFile`，避免遗漏。

---

## 5. mmap 支持的现状与改造点

来源：`/home/developer/mjq/vector/knowhere/include/knowhere/index/index_table.h`

- `IndexEnum::INDEX_DISKANN` 目前 **未在 mmap 支持表** 中。
- `BaseConfig` 已包含 `enable_mmap`，但 DiskANN 没实现 `DeserializeFromFile`。
- 需要改造 DiskANN 以支持 mmap 加载路径，并补齐 knowhere UT。

---

## 6. 关键代码位置（参考）

### k‑NN 插件
- `KNNEngine`: `src/main/java/org/opensearch/knn/index/engine/KNNEngine.java`
- `NativeIndexWriter`: `src/main/java/org/opensearch/knn/index/codec/nativeindex/NativeIndexWriter.java`
- `KNN80CompoundFormat`: `src/main/java/org/opensearch/knn/index/codec/KNN80Codec/KNN80CompoundFormat.java`
- `KNNCodecUtil`: `src/main/java/org/opensearch/knn/index/codec/util/KNNCodecUtil.java`

### knowhere
- DiskANN 实现：`/home/developer/mjq/vector/knowhere/src/index/diskann/diskann.cc`
- DiskANN 配置：`/home/developer/mjq/vector/knowhere/src/index/diskann/diskann_config.h`
- mmap 支持表：`/home/developer/mjq/vector/knowhere/include/knowhere/index/index_table.h`

### Milvus（参考参数/文件管理）
- DiskANN 参数：`/home/developer/mjq/vector/milvus/internal/core/src/index/Meta.h`
- FileManager 实现：`/home/developer/mjq/vector/milvus/internal/core/src/storage/DiskFileManagerImpl.*`

---

## 7. 统一命名约定

- engine：`knowhere`
- method：`diskann`
- k‑NN 文件扩展名建议：`.knowhere`
- DiskANN 多文件建议采用 `.diskann_*` 角色命名 + compound 后缀 `c`

---

## 8. 常见踩坑

- 忘记通过 Lucene Directory 写文件 → SegmentInfo 不记录 → Snapshot/Replica 丢失文件
- 只记录固定文件名 → DiskANN 新增文件时遗漏
- mmap 开启但 DiskANN 未实现 `DeserializeFromFile` → Load 失败
