# Task1: Java Engine/Method/Params Support for Knowhere DiskANN

本任务实现 k-NN 插件 Java 层对 knowhere/diskann 的引擎、方法与参数支持，并补齐对应 UT。

## 目标与范围
- 新增引擎：`knowhere`
- 新增方法：`diskann`
- 新增 query method parameters：`search_list`, `beamwidth`（最小版本 `Version.V_3_0_0`）
- 引擎文件扩展名：`.knowhere`，版本号 `"10"`（对齐 knowhere current_version）
- 仅覆盖 Java 层（JNI/manifest/mmap 在后续任务）

## 关键实现点
1. **KNNEngine**
   - 增加 `KNOWHERE` 枚举与解析（`getEngine` / `getEngineNameFromPath`）
   - `CUSTOM_SEGMENT_FILE_ENGINES` 增加 KNOWHERE
   - `ENGINES_SUPPORTING_FILTERS` / `ENGINES_SUPPORTING_NESTED_FIELDS` 增加 KNOWHERE
   - `MAX_DIMENSIONS_BY_ENGINE` 增加 KNOWHERE（16_000）

2. **Knowhere 引擎**
   - 新增 `Knowhere` 继承 `NativeLibrary`
   - `version = "10"`, `extension = ".knowhere"`
   - 方法集合仅 `diskann`
   - `KnowhereMethodResolver` 约束：
     - 禁止 training context
     - Compression 仅允许 `x1` 或未配置
     - Mode 仅允许 `ON_DISK` 或未配置

3. **DiskANN 方法**
   - 新增 `DiskANNMethod`（`AbstractKNNMethod`）
   - 支持空间：`L2`, `INNER_PRODUCT`, `COSINESIMIL`, `UNDEFINED`
   - 支持数据类型：`FLOAT`
   - build 参数（method parameters）：
     - `max_degree` (int, default 48, range 1..2048)
     - `search_list_size` (int, default 128, range >=1)
     - `pq_code_budget_gb_ratio` (double, default 0, range >=0)
     - `build_dram_budget_gb` (double, default 0, range >=0)
     - `disk_pq_dims` (int, default 0, range >=0)
     - `accelerate_build` (bool, default false)
     - `num_build_thread` (int, optional, range 1..availableProcessors)
     - `search_cache_budget_gb_ratio` (double, default 0, range >=0)
     - `warm_up` (bool, default false)
     - `use_bfs_cache` (bool, default false)
   - query 参数：
     - `search_list` (int, >0)
     - `beamwidth` (int, >0)
   - 不暴露：
     - `pq_code_budget_gb`, `search_cache_budget_gb`, `min_k`, `max_k`, `filter_threshold`

4. **Query 参数解析**
   - `MethodParameter` 增加 `SEARCH_LIST` / `BEAMWIDTH`（`Version.V_3_0_0`）
   - `KNNQueryBuilder` 增加对应 `ParseField`

## 测试用例
- `KNNEngineTests`：新增 knowhere 解析与扩展名识别覆盖
- `MethodParametersParserTests`：新增 `search_list`/`beamwidth` 解析与校验
- `KnowhereTests`：diskann 参数校验、space 类型、vector data type、resolver 模式约束

## 产出
- 代码与 UT
- `docs/diskann_integration_plan.md` 中 Step 1 引用本文件
