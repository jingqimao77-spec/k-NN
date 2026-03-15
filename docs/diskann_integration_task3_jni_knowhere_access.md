# Task 3: JNI & Knowhere Access for DiskANN Integration

本任务实现 k-NN 插件与 knowhere 引擎的 JNI 接入，打通 Java 层与 C++ 层 DiskANN 的 build/load/query 路径。

## 目标与范围
- 新增 Java JNI 服务：`KnowhereService`
- 新增 C++ JNI 实现：`org_opensearch_knn_jni_KnowhereService`
- 新增 Knowhere 包装层：`knowhere_wrapper`
- 打通 `JNIService` 路由，支持 `KNNEngine.KNOWHERE`
- CI/CD build 支持：更新 `jni/CMakeLists.txt` 以集成 knowhere

## 关键实现点

### 1. Java 层：`KnowhereService.java`
- 模仿 `FaissService`，负责加载 `opensearchknn_knowhere` 动态库。
- 定义 native 方法：
  - `initLibrary()`: 初始化 knowhere。
  - `createIndex(int[] ids, long vectorsAddress, int dim, String indexPath, Map<String, Object> parameters)`: 调用 knowhere build。
    - *注意*：相比 Faiss/NMSLIB，这里传递 `indexPath` (即 `index_prefix`)，因为 DiskANN 自行管理多文件写入。
  - `loadIndex(String indexPath, Map<String, Object> parameters)`: 调用 knowhere deserialize。
  - `queryIndex(long indexPointer, float[] queryVector, int k, Map<String, ?> methodParameters)`: 调用 knowhere search。
  - `free(long indexPointer)`: 释放 knowhere 索引对象。

### 2. JNI 路由：`JNIService.java`
- 在 `createIndex`, `loadIndex`, `queryIndex`, `free` 等方法中增加对 `KNNEngine.KNOWHERE` 的分支处理。
- `createIndex`: 针对 KNOWHERE 引擎，从 `parameters` 中提取或通过上下文推导出 `index_prefix` 传递给 `KnowhereService`。

### 3. C++ JNI 层：`org_opensearch_knn_jni_KnowhereService.cpp`
- 实现对应的 JNI 接口。
- 维护 `knowhere` 的全局初始化状态。
- 将 Java 对象（Map, float array 等）转换至 C++ 结构。

### 4. Knowhere 包装层：`knowhere_wrapper.cpp`
- 封装 knowhere 的核心调用逻辑。
- `CreateIndex`:
  - 构造 `knowhere::DataSet` (vectors + ids)。
  - 根据 `parameters` 构造 `knowhere::Config`。
  - 调用 `index.Build()`。
  - 调用 `index.Serialize()` (对于 DiskANN 实际上是触发文件写入到 `index_prefix`)。
- `LoadIndex`:
  - 调用 `index.Deserialize()`。
  - 处理 mmap/native 加载模式选择。
- `QueryIndex`:
  - 构造 query `DataSet`。
  - 调用 `index.Search()` 并将结果转换为 `KNNQueryResult`。

### 5. 构建系统与依赖：`jni/CMakeLists.txt`
- **使用预编译库**：不再从源码编译 `knowhere`，直接链接 `/home/developer/mjq/vector/knowhere/build/Release/libknowhere.so`。
- **CMake 配置**：
  - 使用 `find_library` 或直接指定绝对路径定义 `KNOWHERE_LIB`。
  - `add_library(opensearchknn_knowhere SHARED ...)`。
  - `target_link_libraries(opensearchknn_knowhere ${KNOWHERE_LIB} ${TARGET_LIB_UTIL} ...)`。
- **头文件路径**：
  - 需包含 `knowhere` 及其依赖（如 `faiss`, `diskann`）的头文件路径。
  - `target_include_directories(opensearchknn_knowhere PRIVATE ...)`。
- **运行时加载**：
  - 确保 `libknowhere.so` 在库加载路径中，或在 `KnowhereService.java` 中也通过 `System.load()` 加载。

## 测试用例
- **JNI Native Unit Tests**:
  - 在 `jni/tests` 下新增 `knowhere_wrapper_test.cpp`。
  - 验证：Build (DiskANN) -> Save -> Load -> Search 全流程在 C++ 层正确。
- **Java JNI Tests**:
  - `org.opensearch.knn.jni.KnowhereServiceTests`。
  - 验证：Java 调用 native 方法不 crash，参数传递正确。

## 产出
- `KnowhereService.java`
- `org_opensearch_knn_jni_KnowhereService.h/cpp`
- `knowhere_wrapper.h/cpp`
- `jni/CMakeLists.txt` (Modify)
- `JNIService.java` (Modify)
- `docs/diskann_integration_plan.md` 中 Step 3 引用本文件
