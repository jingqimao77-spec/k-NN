# Knowhere DiskANN IDMap-like 设计

> 目标：为 knowhere DiskANN 增加一层可选的 `IDMap-like` 能力，在不改变 DiskANN 内部 `internal_id` 语义的前提下，为 OpenSearch 提供 `segment docID` 语义返回；同时保持对 Milvus 等已在上层做映射系统的兼容性。

---

## 1. 背景与问题

### 1.1 OpenSearch 的需求

在 OpenSearch k-NN 插件中，native engine 查询结果会直接进入 Lucene/OpenSearch 的 collector 流程。因此对 OpenSearch 而言，native engine 返回的 id 需要满足：

- `KNNQueryResult.id == segment docID`

Faiss 在当前 k-NN 集成中通过 `IndexIDMap` 满足该契约，即：

- 底层索引仍使用自己的内部编号
- 通过 `IDMap` 包装层向上返回外部 id

### 1.2 Knowhere DiskANN 的现状

当前 knowhere DiskANN 的实现中：

- `Search()` 返回的是 DiskANN internal label
- `internal_id_to_most_external_id_map_` 主要用于 bitset/filter 检查
- 查询结果本身并不会自动映射为外部 id

这意味着当前 knowhere DiskANN 不能直接满足 OpenSearch 所要求的 `segment docID` 返回语义。

### 1.3 Milvus 的参考意义

Milvus 的常规做法是：

- bitset 默认是 row/offset 空间
- 若存在 offset mapping，由上层先 `TransformBitset`
- 搜索结果出来后再由上层 `TransformOffset`

因此，Milvus 并不要求 knowhere 默认输出外部 id。对于 Milvus 而言，保持 knowhere 内部 offset/internal 语义通常更自然。

### 1.4 设计目标

本设计希望同时满足两类上层系统：

- OpenSearch：上层没有额外做 ID 变换，希望 knowhere 直接输出 `segment docID`
- Milvus：上层已具备 offset/id 映射能力，希望 knowhere 默认保持 internal/offset 语义

因此，本设计不把 ID 映射做成全局默认行为，而是做成 knowhere DiskANN 的可选能力。

---

## 2. 术语定义

- `internal_id`
  - DiskANN/graph 内部连续编号
  - 取值范围通常为 `[0, Count)`

- `external_id`
  - 上层系统希望看到的编号
  - 不在 knowhere 内写死成某种具体含义

- `segment docID`
  - OpenSearch 场景下的 `external_id`

- `IDMap-like`
  - 参考 Faiss `IDMap` 的设计思想
  - 底层索引保持 internal id
  - 映射层负责 `internal_id <-> external_id` 转换

在 OpenSearch 集成场景中：

- `external_id == segment docID`

---

## 3. 设计原则

### 3.1 DiskANN 内部语义保持不变

- DiskANN 内核仍使用连续 `internal_id`
- build/search/filter 的底层执行逻辑仍围绕 `internal_id`
- 不要求 DiskANN graph 原生持有 OpenSearch docID

### 3.2 映射能力是可选开关

- 对 OpenSearch：开启映射能力
- 对 Milvus：默认关闭映射能力
- 不改变现有 knowhere 其他 index 的默认行为

### 3.3 对外语义统一

在启用映射时，knowhere DiskANN 对外统一使用 `external_id` 语义，覆盖：

- search 返回值
- filter bitset 检查
- by-id 接口
- iterator / range search 返回值

### 3.4 文件生命周期与 DiskANN 多文件方案对齐

- ID 映射信息不内嵌进单个核心索引文件
- 使用 sidecar 文件随 DiskANN 一起管理
- sidecar 文件参与 manifest / compound / snapshot / restore / replica 生命周期

---

## 4. 整体方案

### 4.1 总体思路

参考 Faiss `IDMap` 的核心思想，但不机械复制 Faiss 的类结构：

- DiskANN 内部继续使用 `internal_id` 建图与搜索
- knowhere DiskANN 节点内部可选持有映射：
  - `internal_id -> external_id`
  - `external_id -> internal_id`
- 开启映射后：
  - search 结果在 knowhere 内部从 `internal_id` 映射为 `external_id`
  - filter bitset 检查使用 `internal_id -> external_id`
  - by-id 接口使用 `external_id -> internal_id`

### 4.2 为什么不直接照搬 Faiss `IndexIDMap`

虽然语义上应参考 Faiss，但 DiskANN 的约束与 Faiss 不同：

- DiskANN 是多文件 immutable index
- build/load 强依赖 `index_prefix` 和 `FileManager`
- OpenSearch 侧已有 manifest / compound / sidecar 文件管理需求

因此更适合把 `IDMap-like` 能力直接挂在 `DiskANNIndexNode` 中，而不是强行新增一个 Faiss 风格 wrapper index 类型。

---

## 5. 设计细节

## 5.1 挂载位置

映射能力直接放在 `DiskANNIndexNode` 内部，不新增 wrapper node。

原因：

- DiskANN 的 build/load/search/filter/by-id 都要受影响
- 文件管理与 sidecar 生命周期属于 DiskANN 核心行为
- knowhere 当前 DiskANN 实现已经有 `internal_id_to_most_external_id_map_` 这类映射扩展点

建议新增的成员概念：

- `enable_external_id_map_`
  - 是否启用 `IDMap-like` 能力

- `internal_id_to_external_id_`
  - 顺序数组
  - 下标即 `internal_id`
  - 值为 `external_id`

- `external_id_to_internal_`
  - 反向查找表
  - 用于 by-id 接口和未来可能的扩展

如果当前实现需要兼容 `BitsetView::set_out_ids(const uint32_t*)`，则 v1 优先保持 `uint32_t/int32` 范围，与 OpenSearch `segment docID` 保持一致。

---

## 5.2 配置开关

为 DiskANNConfig 增加可选开关，用于决定是否开启映射能力。

建议能力级配置：

- `enable_external_id_map: bool`
  - `false`：默认行为，兼容 Milvus 等现有调用方
  - `true`：启用 `IDMap-like` 能力，适配 OpenSearch

- `external_id_map_file`
  - 可显式指定，也可由 `index_prefix` 推导固定文件名
  - v1 更推荐由 `index_prefix` 推导，避免上层传参分叉

---

## 5.3 Build 路径

### 目标

在 build 阶段保存上层传入的 `external_id`，但不改变 DiskANN 使用 `internal_id` 的底层语义。

### 输入语义

- dataset rows：向量数据
- dataset ids：上层 `external_id`
- 对 OpenSearch，这里的 `external_id` 就是 `segment docID`

### 处理流程

1. 读取 `dataset->GetIds()`
2. 校验 `rows == ids_count`
3. 生成连续 `internal_id = [0, 1, 2, ...]`
4. DiskANN build 仍按 `internal_id` 语义执行
5. 若 `enable_external_id_map = true`
   - 生成 `internal_id_to_external_id` sidecar 文件
   - 顺序与 `internal_id` 完全一致
6. 通过 `FileManager->AddFile()` 将 sidecar 文件纳入生命周期

### 关键约束

- 不把 OpenSearch `segment docID` 直接当作 DiskANN graph label
- 这样可以避免与当前 `Search()` 返回 internal label 的实现矛盾

---

## 5.4 Load 路径

### 目标

在 load 阶段恢复 ID 映射语义，使 DiskANN 查询能够在 knowhere 内完成 external-id 转换。

### 处理流程

1. 正常加载 DiskANN 主索引文件
2. 若 `enable_external_id_map = true`
   - 加载 `internal_id_to_external_id` sidecar 文件
   - 校验映射条目数量必须等于 `Count()`
   - 构建 `external_id_to_internal` 内存映射
3. 若 `enable_external_id_map = false`
   - 不加载 sidecar 映射
   - 保持当前 internal-id 语义

### 失败策略

以下情况应直接 load 失败：

- sidecar 文件缺失
- 映射条目数与 `Count()` 不一致
- external_id 重复
- sidecar 文件损坏或版本不兼容

不做静默 fallback，避免 query/filter 语义漂移。

---

## 5.5 Search 路径

### 映射关闭时

- 保持当前行为
- `Search()` 返回 internal id

### 映射开启时

1. 底层 `cached_beam_search()` 仍返回 `internal_id`
2. 在 knowhere 内部结果集出栈前，将每个 `internal_id` 转换为 `external_id`
3. 再生成返回给上层的数据集

### 关键要求

- `-1` sentinel 保持不变
- 返回顺序和距离不变，只替换 label/id
- 上层无需额外做 ID 转换

---

## 5.6 Filter / Bitset 路径

### 现状

knowhere 的 `BitsetView` 默认语义是：

- bit 位对应当前搜索空间中的 index/offset

若显式调用 `set_out_ids(...)`，则其行为变为：

- 先将 internal index 映射到 `out_id`
- 再根据 `out_id` 去检查 bitset

### 本设计的用法

#### 映射关闭

- bitset 直接按 internal-id 空间解释

#### 映射开启

- bitset 按 `external_id` 空间解释
- query 期间调用：
  - `bitset.set_out_ids(internal_id_to_external_id_.data(), internal_id_to_external_id_.size())`
- 这样 `bitset.test(internal_id)` 实际检查的是对应 `external_id` 的 bit 位

### 设计价值

- 不需要额外构造一份 internal bitmap
- 复用 knowhere 现有 bitset 映射机制
- 与 Milvus 的既有逻辑不冲突

---

## 5.7 By-id 接口

`GetVectorByIds()` 与 `CalcDistByIDs()` 必须与 search/filter 保持一致语义。

### 映射关闭

- 入参按 internal id 解释
- 保持现有行为

### 映射开启

- 入参按 `external_id` 解释
- 先做 `external_id -> internal_id` 转换
- 再访问 DiskANN 原生接口

### 失败策略

出现以下任一情况直接报错：

- unknown external_id
- external_id 查找失败
- 反向映射表损坏

不能把 external_id 错当成 internal_id 继续执行，否则会产生 silent corruption。

---

## 5.8 Iterator / Range Search

为了保持接口一致性，iterator 和 range search 也必须遵守同样的语义切换。

### 映射关闭

- iterator / range search 返回 internal id

### 映射开启

- iterator / range search 返回 external id

### 原则

- 普通 search、iterator、range search 的 id 语义必须一致
- 不能出现 search 返回 docID，而 iterator 返回 internal_id 的分裂行为

---

## 5.9 文件与 manifest

### sidecar 文件

需要新增一个固定命名的 sidecar 文件，例如：

- `<index_prefix>_external_ids.bin`

v1 不强制此处冻结最终文件名，但必须满足：

- 能从 `index_prefix` 稳定推导
- 可被 manifest 枚举
- 可参与 compound/non-compound 流程

### 文件内容建议

v1 建议保持简单：

- header
  - version
  - count
- body
  - `count` 个定长 `external_id`

### OpenSearch 集成要求

- sidecar 文件作为 engine sidecar 记入 `.knowhere` manifest
- compound/non-compound、snapshot/restore、replica 都要带上该文件

### Milvus 兼容性

- `enable_external_id_map = false` 时不生成该文件
- 现有行为不受影响

---

## 6. 模式矩阵

| 模式 | build 输入 ids | search 返回 | filter bitset 语义 | by-id 语义 |
| --- | --- | --- | --- | --- |
| 映射关闭 | internal/offset 语义或上层自定义 | internal_id | internal/offset 空间 | internal_id |
| 映射开启 | external_id | external_id | external_id 空间 | external_id |

对 OpenSearch：

- 使用“映射开启”模式
- `external_id == segment docID`

对 Milvus：

- 默认使用“映射关闭”模式
- 若未来 Milvus 某场景需要，也可单独评估是否开启

---

## 7. 兼容性与风险

### 7.1 对 OpenSearch 的收益

- 上层无需额外实现 docID 映射层
- 保持 `KNNQueryResult.id == segment docID`
- 适合当前 k-NN 插件的 collector 契约

### 7.2 对 Milvus 的兼容性

- 默认关闭映射能力，不影响当前 offset mapping 路径
- 不改变其上层 `TransformBitset/TransformOffset` 逻辑

### 7.3 主要风险

- search/filter/by-id 三条路径语义不一致
- iterator / range search 忽略映射
- sidecar 文件丢失导致 load 后语义变化
- external_id 重复导致反向映射不确定
- 未来若引入 64-bit id，需要同步扩展 `BitsetView` 映射接口

### 7.4 控制策略

- 所有映射异常在 load/build 阶段尽早失败
- 将模式切换做成显式配置，不做隐式推断
- 在测试中强制覆盖 search/filter/by-id/iterator 一致性

---

## 8. 非目标

本设计 v1 不包含以下内容：

- 不为所有 knowhere index 抽象通用 `IndexIDMapNode<T>` 基类
- 不修改 Milvus 上层 offset mapping 逻辑
- 不把 sidecar 文件格式设计得过于复杂
- 不在本次设计中实现 64-bit external id 全量支持
- 不在本次设计中实现代码，仅作为实现基线

---

## 9. 实现建议

推荐按以下顺序实现：

1. 在 DiskANNConfig 中增加开关
2. 在 DiskANN build/load 路径中增加 sidecar 文件处理
3. 在 Search 路径中增加结果映射
4. 在 filter 路径中启用 `set_out_ids(...)`
5. 在 by-id 接口中增加反向映射
6. 补齐 iterator / range search
7. 最后对 OpenSearch manifest / compound / JNI 做接入

---

## 10. 详细测试计划

## 10.1 单元测试：映射数据结构

### 目标

验证 `internal_id <-> external_id` 映射本身的正确性。

### 用例

- 构建顺序映射：`[0,1,2]`
  - internal 到 external 一一对应
  - external 到 internal 一一对应

- 构建乱序映射：`[10,4,99]`
  - `internal 0 -> external 10`
  - `internal 1 -> external 4`
  - `internal 2 -> external 99`
  - 反向查找正确

- 重复 external_id
  - 例如 `[10,4,10]`
  - 必须失败

- 空映射
  - 当 `Count() == 0` 时允许
  - 否则不允许 sidecar 为空

---

## 10.2 Build 测试

### 目标

验证开启映射时 build 生成 sidecar 文件，关闭映射时不生成。

### 用例

- 映射关闭 build
  - 不生成 sidecar 文件
  - DiskANN 主文件集合正常

- 映射开启 build
  - 生成 sidecar 文件
  - sidecar 条目数与 rows 一致
  - sidecar 顺序与 build 输入 `ids` 顺序一致

- 非连续 external_id
  - 例如 `[100, 7, 42, 9]`
  - sidecar 文件应原样记录

- 空数据 build
  - 根据 DiskANN 当前行为判定是否允许
  - 若不允许，应在 build 阶段直接失败

---

## 10.3 Load 测试

### 目标

验证 sidecar 文件能被正确加载，异常能被显式拒绝。

### 用例

- 映射关闭 load
  - 不读取 sidecar
  - 行为与当前实现一致

- 映射开启 load
  - 成功读取 sidecar
  - `internal_id_to_external_id` 长度等于 `Count()`
  - `external_id_to_internal` 构建成功

- sidecar 缺失
  - load 直接失败

- sidecar 条目数少于 `Count()`
  - load 失败

- sidecar 条目数大于 `Count()`
  - load 失败

- sidecar 中存在重复 external_id
  - load 失败

- sidecar header/version 不匹配
  - load 失败

---

## 10.4 Search 测试

### 目标

验证开启映射后，search 对上返回 external_id，而不是 internal_id。

### 用例

- 映射关闭搜索
  - 返回 internal_id
  - 与当前行为一致

- 映射开启搜索
  - build 输入 ids：`[10,4,99]`
  - 查询返回的 id 只能是 `10/4/99`
  - 不能返回 `0/1/2`

- topK 补齐
  - 若结果不足，`-1` sentinel 不应被映射

- 多 query 批量搜索
  - 每个 query 的结果都应完成映射

- 非连续 / 稀疏 external_id
  - 例如 `[3,100,1000,65535]`
  - 结果仍应正确返回这些 external_id

---

## 10.5 Filter / Bitset 测试

### 目标

验证 bitset 在开启映射后按 external_id 语义工作。

### 用例

- 映射关闭 + internal bitset
  - internal bitset 过滤正确

- 映射开启 + external bitset
  - 屏蔽某个 external_id，对应 internal 命中被过滤

- 多个 internal_id 映射到不同 external_id
  - bitset 行为逐一正确

- 稀疏 external bitset
  - external_id 最大值远大于 Count
  - bitset 只要位图空间足够，仍应正确过滤

- bitset 越界
  - 若 `out_id >= num_bits`
  - 按当前 `BitsetView::test()` 语义，该 id 被视为过滤掉
  - 需要明确记录并测试

- 空 bitset
  - 不过滤任何结果

---

## 10.6 By-id 测试

### 目标

验证 `GetVectorByIds()` 与 `CalcDistByIDs()` 在映射开启后按 external_id 解释。

### 用例

- 映射关闭
  - internal id 查询正确

- 映射开启
  - external_id 输入先转 internal_id 再执行
  - 返回结果正确

- unknown external_id
  - 接口直接失败

- 混合合法/非法 external_id
  - 接口整体失败，不做部分成功

- 重复 external_id 输入
  - 应返回重复项对应结果，或按当前接口约束明确行为

---

## 10.7 Iterator / Range Search 测试

### 目标

保证普通 search、iterator、range search 的 id 语义一致。

### 用例

- 映射关闭
  - iterator/range 返回 internal_id

- 映射开启
  - iterator/range 返回 external_id

- iterator 批次输出
  - 每个 batch 都要映射

- 普通 search 与 iterator 对比
  - 返回的候选 id 语义一致

---

## 10.8 Persistence / File 管理测试

### 目标

验证 sidecar 文件与 DiskANN 主文件同生命周期。

### 用例

- build -> load -> search
  - 前后结果一致

- manifest 枚举
  - sidecar 文件被 manifest 记录

- non-compound
  - sidecar 原始文件名保留

- compound
  - sidecar 随引擎文件一起走 compound 复制流程

- sidecar 文件缺失
  - load 失败

- 删除 sidecar 后 restore/reload
  - 明确失败

---

## 10.9 OpenSearch 集成测试

### 目标

验证对 OpenSearch 的最终契约成立。

### 用例

- build 输入为 Lucene segment docID
  - query 返回 `KNNQueryResult.id == segment docID`

- filter 输入按 segment docID 构造 bitset
  - 过滤结果正确

- snapshot / restore
  - 恢复后结果 id 仍是 segment docID

- replica
  - 主分片与副本返回相同 docID 语义

- compound / non-compound
  - 两种模式都正确加载 id map sidecar

---

## 10.10 回归测试

### 目标

确保默认行为不受影响。

### 用例

- 不开启映射时，Milvus/knowhere 现有 DiskANN 测试全部通过
- 其他 knowhere index 不受影响
- `enable_external_id_map = false` 时行为与当前版本一致

---

## 11. 结论

本设计将 Faiss `IDMap` 的核心思想迁移到 knowhere DiskANN，但不照搬 Faiss 的对象结构。

最终方案是：

- DiskANN 内部保持 `internal_id`
- knowhere 提供可选 `IDMap-like` 能力
- OpenSearch 开启该能力并使用 `segment docID` 作为 `external_id`
- Milvus 默认关闭该能力，继续沿用上层 offset/id 映射

这样既能满足 OpenSearch 的 docID 契约，也能兼容 Milvus 等已有调用方的现有语义与实现路径。
