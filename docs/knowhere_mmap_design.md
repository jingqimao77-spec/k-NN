# Knowhere DiskANN Mmap Support Design Document

## 1. Background
The OpenSearch k-NN plugin is integrating the DiskANN algorithm via the Knowhere engine. To optimize memory usage during search, especially for large-scale datasets that exceed available RAM, mmap (memory-mapped files) support is required for DiskANN indices. This allows the OS to manage the loading and caching of index pages from disk to memory transparently.

## 2. Technical Architecture

### 2.1 Mmap Reader Implementation
DiskANN uses an `AlignedFileReader` interface to perform I/O operations. Currently, `LinuxAlignedFileReader` is used, which performs asynchronous I/O (AIO) from disk into user-space buffers.

To support mmap, we introduce `MmapAlignedFileReader`:
- **Mapping**: It uses `diskann::MemoryMapper` to map the disk index file into a contiguous virtual memory space.
- **Reading**: The `read(std::vector<AlignedRead>& read_reqs, ...)` method is implemented using `memcpy`. Since the data is already in the virtual address space (mapped), we simply copy from the source address (base + offset) to the provided buffer.
- **Asynchronous Simulation**: Since mmap access is synchronous from the CPU perspective (page faults are handled by the OS), the `submit_req` and `get_submitted_req` methods will perform immediate copies.

### 2.2 Knowhere Engine Integration
- **Index Table**: `include/knowhere/index/index_table.h` will be updated to include `DISKANN` in the list of indices supporting mmap.
- **Index Node**: `DiskANNIndexNode` will be updated to:
    - Implement `DeserializeFromFile(const std::string& filename, std::shared_ptr<Config> config)`.
    - Check the `enable_mmap` flag in the configuration.
    - If `enable_mmap` is true, instantiate `MmapAlignedFileReader` and pass it to the underlying `PQFlashIndex`.
    - Handle the multi-file nature of DiskANN by using the provided `filename` as the `index_prefix`.

## 3. Implementation Details

### 3.1 MmapAlignedFileReader Class
```cpp
class MmapAlignedFileReader : public AlignedFileReader {
public:
    MmapAlignedFileReader() : mapper_(nullptr), is_open_(false) {}
    virtual void open(const std::string& fname) override {
        mapper_ = std::make_unique<diskann::MemoryMapper>(fname);
        buf_ = mapper_->getBuf();
        is_open_ = true;
    }
    virtual void close() override {
        mapper_.reset();
        is_open_ = false;
    }
    virtual void read(std::vector<AlignedRead>& read_reqs, IOContext& ctx, bool async = false) override {
        for (auto& req : read_reqs) {
            std::memcpy(req.buf, buf_ + req.offset, req.len);
        }
    }
    // ... submit_req and get_submitted_req ...
private:
    std::unique_ptr<diskann::MemoryMapper> mapper_;
    char* buf_;
    bool is_open_;
};
```

### 3.2 Configuration
A new configuration parameter `enable_mmap` (boolean) will be passed through the `DiskANNConfig`. When set to `true`, the engine will trigger the mmap loading path.

## 4. Verification Plan

### 4.1 Unit Tests
- `tests/ut/test_diskann.cc` will include a test case that:
    1. Builds a DiskANN index.
    2. Loads the index with `enable_mmap = true`.
    3. Performs search and compares results with a non-mmap (native) load.
    4. Verifies that no errors occur during search.

### 4.2 Performance & Resource Monitoring
- Verify that the resident set size (RSS) of the process remains low when mmap is used, even if the index size is large.
- Verify that the page cache is utilized effectively by the OS.
