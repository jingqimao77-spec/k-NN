# DiskANN 集成到 k-NN 插件：文件管理方案

> 文档路径：`docs/analysis/diskann_file_management_plan.md`  
> 创建日期：2026-03-13  
> 涉及代码：`knowhere/src/index/diskann/diskann.cc`，`KNN80CompoundFormat.java`，`KNNCodecUtil.java`

---

## 1. 背景与核心挑战

### 1.1 Faiss 文件管理模式（现有）

Faiss 索引在 flush/merge 时产生 **1 个文件**，通过 Lucene 流式 IO 接口写入：

```
state.directory.createOutput("_0_165_my_field.faiss")
  → TrackingDirectoryWrapper 记录
  → flush 后 si.setFiles({..., "_0_165_my_field.faiss"})
  → createCompoundFile 时 copyFrom(.faiss → .faissc)
  → 最终 SegmentInfo 包含 .faissc，.faiss 被删除
```

### 1.2 DiskANN 文件管理模式（需集成）

DiskANN 产生 **6-9 个文件**，全部写到本地磁盘某 `prefix` 路径下：

```
{prefix}_pq_pivots.bin                        ← PQ 训练中心点（必需）
{prefix}_pq_pivots.bin.rearrangement_perm.bin ← PQ 重排列（必需）
{prefix}_pq_pivots.bin.chunk_offsets.bin      ← PQ chunk 偏移（必需）
{prefix}_pq_pivots.bin.centroid.bin           ← PQ centroid（必需）
{prefix}_pq_compressed.bin                    ← PQ 编码向量（必需）
{prefix}_disk.index                           ← 磁盘图索引（必需，最大文件）
{prefix}_disk.index.max_base_norm.bin         ← IP/COSINE 归一化（可选）
{prefix}_disk.index.medoids.bin               ← medoids（可选）
{prefix}_cached_nodes.bin                     ← 节点缓存（可选）
{prefix}_sample_data.bin                      ← 预热采样（可选）
```

DiskANN 没有类似 Faiss 的 `IOWriter` 流式接口，必须先写到本地磁盘、再通过 **`FileManager`** 接口管理文件的上传（`AddFile`）和下载（`LoadFile`）。

---

## 2. Lucene Directory 物理路径结构

```
{opensearch.data.path}/
  nodes/
    {node-id}/                           ← 通常为 0
      indices/
        {index-uuid}/
          {shard-id}/
            index/                       ← ← Lucene FSDirectory 对应的目录
              _0_165_my_field.faiss      ← Faiss 文件示例
              _0.cfe
              _0.cfs
              segments_N
            translog/
```

**`state.directory`（`TrackingDirectoryWrapper`）** unwrap 后对应：  
`{data.path}/nodes/{node-id}/indices/{index-uuid}/{shard-id}/index/`

---

## 3. 文件命名规则设计

### 3.1 现有 Faiss 命名规则（`buildEngineFileName`）

```java
// KNNCodecUtil.java:50
// 格式：{segmentName}_{version}_{fieldName}.{extension}
// 示例：_0_165_my_field.faiss
String name = segmentName + "_" + version + "_" + fieldName + ".faiss";
```

注释示例（`KNNCodecUtil.java:98`）：`_0_165_my_field.faiss`

### 3.2 DiskANN 命名规则（新增）

在 Lucene Directory 中，所有 DiskANN 文件通过文件名中的 `.diskann_` 标记区分：

**格式**：`{segmentName}_{version}_{fieldName}.diskann_{role}`

| DiskANN 内部后缀（相对 prefix） | Lucene Directory 中的文件名 | 是否必需 |
|---|---|---|
| `_pq_pivots.bin` | `_0_165_my_field.diskann_pq_pivots` | ✅ 必需 |
| `_pq_pivots.bin.rearrangement_perm.bin` | `_0_165_my_field.diskann_pq_rearr` | ✅ 必需 |
| `_pq_pivots.bin.chunk_offsets.bin` | `_0_165_my_field.diskann_pq_chunks` | ✅ 必需 |
| `_pq_pivots.bin.centroid.bin` | `_0_165_my_field.diskann_pq_centroid` | ✅ 必需 |
| `_pq_compressed.bin` | `_0_165_my_field.diskann_pq_codes` | ✅ 必需 |
| `_disk.index` | `_0_165_my_field.diskann_graph` | ✅ 必需 |
| `_disk.index.max_base_norm.bin` | `_0_165_my_field.diskann_norm` | ⚠️ 可选 |
| `_disk.index.medoids.bin` | `_0_165_my_field.diskann_medoids` | ⚠️ 可选 |
| `_cached_nodes.bin` | `_0_165_my_field.diskann_cache` | ⚠️ 可选 |
| `_sample_data.bin` | `_0_165_my_field.diskann_sample` | ⚠️ 可选 |

经 compound 阶段处理后，Lucene 最终管理的文件名加 `c` 后缀（类比 `.faissc`）：

```
_0_165_my_field.diskann_graph    →    _0_165_my_field.diskann_graphc
_0_165_my_field.diskann_pq_codes →    _0_165_my_field.diskann_pq_codesc
...
```

### 3.3 命名识别规则

```java
// KNN80CompoundFormat 中，Faiss vs DiskANN 文件识别：
"_0_165_my_field.faiss"              // Faiss：.endsWith(".faiss")
"_0_165_my_field.diskann_graph"      // DiskANN：.contains(".diskann_")
```

---

## 4. DiskANN 本地 prefix 设计

### 4.1 prefix 路径

DiskANN 的 `Build()` 需要一个本地路径前缀（prefix），在此前缀下写出所有文件。

**推荐：复用 Lucene index 目录**

```
prefix = {FSDirectory 路径} / {segmentName}_{version}_{fieldName}
       = /data/nodes/0/indices/{uuid}/{shard}/index/_0_165_my_field
```

DiskANN 实际写出的文件（在 build 阶段临时存在）：
```
/data/nodes/0/indices/{uuid}/{shard}/index/_0_165_my_field_pq_pivots.bin
/data/nodes/0/indices/{uuid}/{shard}/index/_0_165_my_field_pq_compressed.bin
/data/nodes/0/indices/{uuid}/{shard}/index/_0_165_my_field_disk.index
...
```

### 4.2 获取 FSDirectory 路径的代码

```java
// NativeIndexWriter / OpenSearchFileManager 中
Directory dir = state.directory;
while (dir instanceof FilterDirectory) {
    dir = ((FilterDirectory) dir).getDelegate();
}
Path indexDirPath = ((FSDirectory) dir).getDirectory();
// = /data/nodes/0/indices/{uuid}/{shard}/index/

String localPrefix = indexDirPath
    .resolve(segmentName + "_" + version + "_" + fieldName)
    .toString();
// = /data/nodes/0/indices/{uuid}/{shard}/index/_0_165_my_field
```

---

## 5. OpenSearchFileManager 实现

DiskANN 需要 `milvus::FileManager` 接口，需新实现 `OpenSearchFileManager`（C++ JNI 层）。

### 5.1 接口定义（C++）

```cpp
// jni/include/opensearch_file_manager.h
class OpenSearchFileManager : public milvus::FileManager {
public:
    // 传入 Lucene Directory 的 JNI 引用、segment 信息
    OpenSearchFileManager(JNIEnv* env, jobject luceneDir,
                          const std::string& localPrefix,
                          const std::string& lucenePrefix);

    // Build 后调用：把本地 DiskANN 文件上传到 Lucene Directory
    // 本地路径 → Lucene 文件名（通过 ROLE_MAP 映射）
    bool AddFile(const std::string& localPath) override;

    // Deserialize 时调用：从 Lucene Directory 下载文件到本地
    // Lucene 文件名（.diskannXc）→ 本地路径（DiskANN 期望名）
    bool LoadFile(const std::string& localExpectedPath) override;

    // 检查 Lucene Directory 中是否存在该文件
    std::optional<bool> IsExisted(const std::string& localPath) override;

private:
    // 本地路径 → Lucene 文件名
    std::string localToLucene(const std::string& localPath) const;
    // Lucene 文件名 → 本地路径（用于 LoadFile）
    std::string luceneToLocal(const std::string& luceneName) const;

    JNIEnv* env_;
    jobject luceneDir_;       // Java Directory 对象（JNI 引用）
    std::string localPrefix_; // /data/.../index/_0_165_my_field
    std::string lucenePrefix_;// _0_165_my_field
};
```

### 5.2 文件名映射表（C++常量）

```cpp
// DiskANN 内部后缀 → Lucene role 名称（双向映射）
static const std::map<std::string, std::string> DISKANN_SUFFIX_TO_ROLE = {
    {"_pq_pivots.bin",                           "diskann_pq_pivots"},
    {"_pq_pivots.bin.rearrangement_perm.bin",    "diskann_pq_rearr"},
    {"_pq_pivots.bin.chunk_offsets.bin",         "diskann_pq_chunks"},
    {"_pq_pivots.bin.centroid.bin",              "diskann_pq_centroid"},
    {"_pq_compressed.bin",                       "diskann_pq_codes"},
    {"_disk.index",                              "diskann_graph"},
    {"_disk.index.max_base_norm.bin",            "diskann_norm"},
    {"_disk.index.medoids.bin",                  "diskann_medoids"},
    {"_cached_nodes.bin",                        "diskann_cache"},
    {"_sample_data.bin",                         "diskann_sample"},
};
// role → DiskANN 后缀（反向映射，用于 LoadFile）
```

### 5.3 AddFile 逻辑（Build 后上传）

```cpp
bool OpenSearchFileManager::AddFile(const std::string& localPath) {
    // 1. 从 localPath 提取 DiskANN 后缀（去掉 localPrefix_ 部分）
    std::string suffix = localPath.substr(localPrefix_.size());
    // suffix = "_disk.index"

    // 2. 查 ROLE_MAP 得到 Lucene role
    auto it = DISKANN_SUFFIX_TO_ROLE.find(suffix);
    if (it == DISKANN_SUFFIX_TO_ROLE.end()) {
        LOG_ERROR("Unknown DiskANN suffix: " + suffix);
        return false;
    }
    // luceneName = "_0_165_my_field.diskann_graph"
    std::string luceneName = lucenePrefix_ + "." + it->second;

    // 3. 打开本地文件，通过 JNI 调用 dir.createOutput(luceneName) 写入 Lucene Directory
    // （类似 Faiss 的 FaissOpenSearchIOWriter，但这里是从文件读而非从内存写）
    return copyLocalFileToLuceneDir(localPath, luceneName);

    // 4. 调用方负责删除本地原始文件（或本函数内删除）
}
```

### 5.4 LoadFile 逻辑（Deserialize 时下载）

```cpp
bool OpenSearchFileManager::LoadFile(const std::string& localExpectedPath) {
    // localExpectedPath = /.../_0_165_my_field_disk.index
    // （DiskANN 通过 get_disk_index_filename(prefix) 构造出来的路径）

    // 1. 提取后缀，查 Lucene 文件名
    std::string suffix = localExpectedPath.substr(localPrefix_.size());
    // suffix = "_disk.index"
    auto it = DISKANN_SUFFIX_TO_ROLE.find(suffix);
    // → role = "diskann_graph"

    // 2. compound 阶段文件名 = lucenePrefix + ".diskann_graphc"（带 c 后缀）
    std::string luceneName = lucenePrefix_ + "." + it->second + "c";

    // 3. 通过 JNI 从 Lucene Directory openInput(luceneName)，写到 localExpectedPath
    return copyLuceneDirFileToLocal(luceneName, localExpectedPath);
}
```

---

## 6. KNN80CompoundFormat 改动

### 6.1 现有代码

```java
// KNN80CompoundFormat.java:55-72（现有）
private void writeEngineFiles(Directory dir, SegmentInfo si, IOContext context,
                               String engineExtension) throws IOException {
    Set<String> engineFiles = si.files().stream()
        .filter(file -> file.endsWith(engineExtension))  // 按扩展名过滤
        .collect(Collectors.toSet());
    Set<String> segmentFiles = new HashSet<>(si.files());
    if (!engineFiles.isEmpty()) {
        for (String engineFile : engineFiles) {
            String engineCompoundFile = engineFile + KNNConstants.COMPOUND_EXTENSION;
            dir.copyFrom(dir, engineFile, engineCompoundFile, context);
        }
        segmentFiles.removeAll(engineFiles);
        si.setFiles(segmentFiles);
    }
}
```

### 6.2 改动后（新增 DiskANN 支持）

```java
@Override
public void write(Directory dir, SegmentInfo si, IOContext context) throws IOException {
    // 1. 处理 Faiss 文件（现有逻辑，按扩展名过滤）
    for (KNNEngine engine : KNNEngine.getEnginesThatCreateCustomSegmentFiles()) {
        if (engine == KNNEngine.FAISS) {
            writeEngineFiles(dir, si, context, engine.getExtension());
        }
    }

    // 2. 处理 DiskANN 多文件（新增，按 .diskann_ 中缀过滤）
    writeDiskANNEngineFiles(dir, si, context);

    // 3. 打包其余文件进 .cfs
    delegate.write(dir, si, context);
}

private void writeDiskANNEngineFiles(Directory dir, SegmentInfo si, IOContext context)
        throws IOException {
    // 识别所有 DiskANN 文件（包含 ".diskann_" 的文件名）
    Set<String> diskannFiles = si.files().stream()
        .filter(f -> f.contains(".diskann_"))
        .collect(Collectors.toSet());

    if (diskannFiles.isEmpty()) return;

    Set<String> segmentFiles = new HashSet<>(si.files());

    for (String diskannFile : diskannFiles) {
        // 生成 compound 版：.diskann_graphc（类比 .faissc）
        String compoundFile = diskannFile + KNNConstants.COMPOUND_EXTENSION;
        // copyFrom 将文件复制到 tracking directory，使其出现在 getCreatedFiles() 中
        dir.copyFrom(dir, diskannFile, compoundFile, context);
    }
    // 从 si.files() 移除原始文件（不打包进 .cfs）
    segmentFiles.removeAll(diskannFiles);
    si.setFiles(segmentFiles);
    // DiskANN .c 版文件留在 tracking dir → createCompoundFile 后被 info.setFiles 保留
}
```

### 6.3 为什么 DiskANN 文件也必须做 copyFrom（关键）

`createCompoundFile` 使用**全新的空 `TrackingDirectoryWrapper`**，结束后调用：
```java
info.setFiles(new HashSet<>(directory.getCreatedFiles()));
```
这会**完全替换** `si.files()`，只保留 `write()` 执行期间通过 `createOutput`/`copyFrom` 创建的文件。

如果 DiskANN 文件不在 `write()` 里重新创建，它们就会**从 SegmentInfo 消失**，最终被 `IndexFileDeleter` 删除。`copyFrom(diskann_graph → diskann_graphc)` 正是为了让 DiskANN 文件"登记"到这个新的 tracking dir 中。

---

## 7. 文件生命周期时序

### 7.1 Flush（写段）阶段

```
NativeIndexWriter.buildAndWriteIndex()
  │
  ├─ 1. DiskANN Build 阶段
  │   ├─ 获取 FSDirectory 路径，构造 localPrefix
  │   │   = /data/indices/{uuid}/{shard}/index/_0_165_my_field
  │   ├─ 构造 OpenSearchFileManager（含 Lucene Dir JNI 引用）
  │   ├─ DiskANN.Build(dataset, config)
  │   │   → 写本地文件：
  │   │     /.../_0_165_my_field_disk.index
  │   │     /.../_0_165_my_field_pq_pivots.bin
  │   │     ... (6-9 个文件)
  │   │
  │   └─ DiskANN 内部调用 file_manager->AddFile(localPath)
  │       → OpenSearchFileManager::AddFile()
  │           → 查映射表：_disk.index → diskann_graph
  │           → luceneName = "_0_165_my_field.diskann_graph"
  │           → state.directory.createOutput("_0_165_my_field.diskann_graph")
  │               → TrackingDirectoryWrapper 记录该文件名 ✓
  │           → 写文件内容（本地 → Lucene Directory）
  │           → 原本地文件删除
  │
  └─ 2. flush 结束：si.setFiles(trackingDir.getCreatedFiles())
         = {_0_165_my_field.diskann_graph,
            _0_165_my_field.diskann_pq_codes,
            _0_165_my_field.diskann_pq_pivots,
            ... ,
            _0.fdt, _0.tim, ...}  ← DiskANN 文件已进入 SegmentInfo ✓
```

### 7.2 createCompoundFile 阶段

```
IndexWriter.createCompoundFile(freshTrackingDir, si, ...)
  │
  ├─ KNN80CompoundFormat.write(freshTrackingDir, si)
  │   │
  │   ├─ writeDiskANNEngineFiles():
  │   │   ├─ 找到 si.files() 中含 ".diskann_" 的文件
  │   │   ├─ for each diskannFile:
  │   │   │   freshTrackingDir.copyFrom(dir, diskannFile, diskannFile+"c", ctx)
  │   │   │   → 创建 _0_165_my_field.diskann_graphc 等
  │   │   │   → freshTrackingDir.createdFileNames 记录 .diskann_graphc ✓
  │   │   └─ si.setFiles(si.files() - diskannFiles)  ← 移除原版
  │   │
  │   └─ delegate.write(freshTrackingDir, si):   ← 打包剩余 Lucene 文件
  │       → 创建 _0.cfs, _0.cfe
  │       → freshTrackingDir.createdFileNames 记录 .cfs, .cfe
  │
  └─ info.setFiles(freshTrackingDir.getCreatedFiles())
       = {_0_165_my_field.diskann_graphc,      ← DiskANN compound 版 ✓
          _0_165_my_field.diskann_pq_codesc,   ✓
          _0_165_my_field.diskann_pq_pivotsc,  ✓
          ... ,
          _0.cfs, _0.cfe}                      ← Lucene compound ✓

     原始 _0_165_my_field.diskann_graph 等被 IndexFileDeleter 删除 ✓
```

### 7.3 Search（读段）阶段

```
OpenSearchFileManager::LoadFile(expectedLocalPath)
  │
  ├─ expectedLocalPath = /.../_0_165_my_field_disk.index
  │   （DiskANN 的 get_disk_index_filename(prefix) 构造）
  │
  ├─ 查映射表：_disk.index → diskann_graph
  ├─ luceneName = "_0_165_my_field.diskann_graphc"（compound 版）
  │
  ├─ dir.openInput("_0_165_my_field.diskann_graphc")
  │   → 从 Lucene Directory 读
  │
  └─ 写到本地 expectedLocalPath
      → /.../_0_165_my_field_disk.index
      → DiskANN pq_flash_index_->load(prefix) 找到文件 ✓
      → 按 sector 对齐 AIO/mmap 读取进行搜索

搜索结束 / IndexReader 关闭时：
  → 删除本地临时文件目录 ✓
  → Lucene Directory 中的 .diskann_graphc 留着（由 IndexFileDeleter 管理）
```

### 7.4 Merge 后清理

```
merge(_0, _1) → _4
  │
  ├─ _4 段产生自己的 DiskANN 文件，注册在 si4.files()
  │
  └─ IndexFileDeleter：
       _0_165_my_field.diskann_graphc  refcount → 0  → 删除 ✓
       _1_165_my_field.diskann_graphc  refcount → 0  → 删除 ✓
       _0.cfs                          refcount → 0  → 删除 ✓
       _4_165_my_field.diskann_graphc  refcount = 1  → 保留 ✓
```

---

## 8. 改动文件清单

### C++/JNI 层（新增）

| 文件 | 改动 |
|------|------|
| `jni/include/opensearch_file_manager.h` | **新增** `OpenSearchFileManager` 类声明 |
| `jni/src/opensearch_file_manager.cpp` | **新增** `OpenSearchFileManager` 实现（含双向映射表） |
| `jni/include/knowhere_diskann_wrapper.h` | **新增** DiskANN JNI wrapper 声明 |
| `jni/src/knowhere_diskann_wrapper.cpp` | **新增** `Build/Deserialize/Search/Free` JNI 实现 |
| `jni/CMakeLists.txt` | **修改** 链接 Knowhere 库、新增源文件 |

### Java 层（修改/新增）

| 文件 | 改动 |
|------|------|
| `KNNEngine.java` | **新增** `KNOWHERE_DISKANN` 枚举值 |
| `KNN80CompoundFormat.java` | **修改** `write()` 新增 `writeDiskANNEngineFiles()` |
| `KNN80CompoundDirectory.java` | **修改** `openInput()` 路由新增 `.diskann_` 前缀文件处理 |
| `KNNCodecUtil.java` | **修改** `getEngineFiles()` 新增对多文件引擎的支持 |
| `NativeIndexWriter.java` | **修改** DiskANN 的 build 路径（LocalPrefix + FileManager 构造） |
| `KnowhereService.java` | **新增** JNI native 方法声明 |

---

## 9. 重要约束与边界条件

### 9.1 DiskANN 文件大小

`_disk.index` 通常是 GB 级别（本质是 sector 对齐的图数据）。`writeDiskANNEngineFiles` 中的 `copyFrom` 会产生一次完整的文件复制，存在内存和 IO 压力。

**优化思路（可选）**：如果 Lucene 版本允许，可以通过 `TrackingDirectoryWrapper.fileNameFilter` 或自定义 Directory 避免实际 copy，降低 IO 放大。

### 9.2 Knowhere DiskANN 的 Faiss 版本

Knowhere 内嵌了自己的 Faiss，需要确认与 k-NN 现有 Faiss 版本是否冲突。建议：
- 使用 Knowhere 提供的 Faiss（用 Knowhere 替换现有的 Faiss 依赖）
- 或者链接时做命名空间隔离

### 9.3 数据类型支持

Knowhere DiskANN 支持 `fp32`、`fp16`、`bf16`，需在 Java 侧的 `VectorDataType` 映射中正确对应。

### 9.4 本地临时文件生命周期

Search 时下载到本地的 DiskANN 文件必须在 `IndexReader`（或对应 `KNNIndexShard`）关闭时清理，避免磁盘泄漏。在 `NativeMemoryCacheManager` 的 evict 回调中加入对应清理逻辑。

---

## 10. mmap 方案分析

### 10.1 为何考虑 mmap

默认方案（第 7.3 节）在 Search 阶段需要将 `_disk.index` **全量下载**到本地，再由 DiskANN 通过 AIO+O_DIRECT 读取。`_disk.index` 通常是 GB 级别，全量下载带来较大的延迟和磁盘 IO 压力。

Lucene 的 `MMapDirectory` 已被 k-NN 用于 Faiss HNSW 的内存节省场景（mmap 文件后通过 JNI 传递 native 地址）。DiskANN 的 `_disk.index` 文件**天然按 sector 对齐**（每个图节点一个 sector），结构适合 mmap 随机访问，因而值得评估 mmap 方案的可行性。

---

### 10.2 两种 mmap 方案

#### 方案 A：下载到本地 + mmap 本地文件

```
Lucene Directory (.diskann_graphc)
  → LoadFile(): 全量下载 → 本地 _disk.index（与默认方案相同）
  → open(O_RDONLY) + mmap()                  ← 替换 AIO+O_DIRECT
  → PQFlashIndex 访问 mmap 内存区域进行搜索
```

对文件管理方案（第 5、7 节）无任何改动，仅替换 DiskANN 内部的 `LinuxAlignedFileReader` 实现。

#### 方案 B：直接 mmap Lucene Directory 文件（省去下载）

```
Lucene MMapDirectory.openInput("_0_165_my_field.diskann_graphc")
  → Java DirectByteBuffer（OS mmap 映射）
  → JNI: DirectBuffer.address() → native void*
  → 传给 PQFlashIndex 的 MemoryMappedReader 实现
  → DiskANN 直接在 Lucene 的 mmap 区域搜索    ← 无需下载到本地
```

`_disk.index` **不再需要下载**，本地临时目录只存 PQ 文件（共 4 个，总计几十 MB）。

需要对 Knowhere 的 `PQFlashIndex` 新增 `MemoryMappedReader` 接口（接受 `void*`+`size` 而非文件路径）。

---

### 10.3 性能对比分析

#### mmap 的优势（page cache 命中时）

对于**单次** beam search 访问 N 个节点，假设其中 H 个热节点在 page cache 中：

```
mmap（page cache 热）:
  H 个热节点 → 直接访问 page cache → 0 次磁盘 IO
  N-H 个冷节点 → page fault → N-H 次磁盘 IO
  总磁盘 IO = N-H 次（少于 O_DIRECT）

O_DIRECT AIO:
  N 个节点 → 全部读磁盘 → N 次磁盘 IO
  总磁盘 IO = N 次（固定）
```

**结论：page cache 命中率高时，mmap 每次搜索的磁盘 IO 更少。**

#### mmap 的劣势（多 shard / 内存压力）

多 shard 场景下，各 shard 的 `_disk.index` 共同竞争 page cache：

1. **热节点被踢出（eviction）**：shard_A 的热节点被 shard_B 的 IO 挤出 page cache，下次访问同一节点产生额外 page fault ——即跨请求的重复磁盘读写。

2. **线程阻塞**：page fault 发生时当前线程**同步阻塞**等待 IO 完成，无法并发处理其他候选节点。而 DiskANN 的 AIO 提交后线程不阻塞，beam search 的 beamwidth 条并发 IO 可以真正并行飞行。

3. **延迟不可预期**：page fault 的处理时间由 OS 调度决定，在高负载下 P99/P999 延迟抖动明显。AIO 的完成时间取决于磁盘和 IO 队列，在可控范围内。

> **注意**：mmap 和 O_DIRECT 在**物理磁盘带宽**竞争上没有区别，多 shard 都会争抢相同的 SSD 带宽。mmap 的额外问题是同一数据块被反复 eviction-refetch 造成的**重复 IO**，以及 page fault 导致的**线程阻塞**。

#### 在 page fault 必然发生时的路径开销对比

| 环节 | mmap（page fault） | O_DIRECT AIO |
|------|---|---|
| 发现缓存未命中 | TLB miss → 页表遍历 → page fault 异常 → 内核异常处理 | 无（直接 `io_submit()`） |
| page cache 操作 | 分配物理页 + 更新页表 | 无 |
| 线程状态 | **同步阻塞**（睡眠等待 IO） | **不阻塞**（可继续处理其他节点） |
| 底层磁盘 IO | 相同 | 相同 |
| 并发能力 | 串行（一个 page fault 一次等待） | 并行（beam width 个 IO 同时飞行） |

---

### 10.4 适用场景对比

| 场景 | 推荐方案 | 原因 |
|------|---------|------|
| 少量 shard（≤3）+ 索引小（≤1 GB）+ RAM 充足 | mmap（方案 B 最优） | 热节点常驻 page cache，IO 少，省去下载 |
| 中等规模（shard ≤10，RAM 尚充足） | mmap 方案 A | 热节点部分命中，减少下载后的 AIO 次数 |
| 多 shard 或大索引（>10 shard 或 >10 GB） | **默认方案（AIO+O_DIRECT）** | mmap eviction 问题严重，AIO 延迟稳定 |
| 超大规模（TB 级） | **默认方案（AIO+O_DIRECT）** | DiskANN 专为此设计，O_DIRECT 避免 page cache 污染 |

---

### 10.5 实施路径与最终建议

**阶段一（默认）**：实施第 5-9 节描述的 AIO+O_DIRECT 方案（`LoadFile` 全量下载 + DiskANN 原生 IO），确保正确性和稳定性。

**阶段二（可选优化）**：添加 `index.knn.diskann.io_mode` 配置项：
- `aio_direct`（默认）：全量下载 + AIO+O_DIRECT
- `mmap_local`：全量下载 + mmap 本地文件（方案 A，较易实现）
- `mmap_lucene`：直接 mmap Lucene Directory（方案 B，需改 Knowhere，省去大文件下载）

**最终结论**：

> 对于 OpenSearch 典型的多 shard 部署场景，**默认使用 AIO+O_DIRECT（全量下载）方案**是最稳健的选择——延迟可预期、无 page cache 竞争、与 DiskANN 算法设计吻合。  
> mmap 方案仅作为单 shard / 小索引 / 内存充足场景的可选优化，通过配置参数开放，**不设为默认值**。
