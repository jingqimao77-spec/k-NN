# Local Knowhere OSB Filter Benchmarks

These helpers create a local-friendly HDF5 dataset for the OSB `vectorsearch` workload and provide Knowhere-specific parameter files for the official relaxed and restrictive efficient-filter benchmark shapes.

## Files

- `generate_small_filter_hdf5.py`: generates a small ANN Benchmarks-style HDF5 with `train`, `test`, `neighbors`, and `attributes`.
- `knowhere-index-attributes.json`: Knowhere DiskANN index template for OSB.
- `knowhere-diskann-relaxed-50k.json`: local relaxed efficient-filter params.
- `knowhere-diskann-restrictive-50k.json`: local restrictive efficient-filter params.

## Generate Datasets

```bash
python3 scripts/osb/knowhere_filter/generate_small_filter_hdf5.py   --kind relaxed   --output /tmp/knowhere-filter-bench/filter_relaxed_50k.hdf5

python3 scripts/osb/knowhere_filter/generate_small_filter_hdf5.py   --kind restrictive   --output /tmp/knowhere-filter-bench/filter_restrictive_50k.hdf5
```

## Sync Into OSB Workload Clone

```bash
cp scripts/osb/knowhere_filter/knowhere-index-attributes.json   /tmp/opensearch-benchmark-workloads/vectorsearch/indices/filters/knowhere-index-attributes.json

cp scripts/osb/knowhere_filter/knowhere-diskann-relaxed-50k.json   /tmp/opensearch-benchmark-workloads/vectorsearch/params/filters/efficient/knowhere-diskann-relaxed-50k.json

cp scripts/osb/knowhere_filter/knowhere-diskann-restrictive-50k.json   /tmp/opensearch-benchmark-workloads/vectorsearch/params/filters/efficient/knowhere-diskann-restrictive-50k.json
```

## Run

```bash
/root/.local/bin/opensearch-benchmark execute-test   --pipeline benchmark-only   --target-hosts 127.0.0.1:9200   --workload-path /tmp/opensearch-benchmark-workloads/vectorsearch   --workload-params /tmp/opensearch-benchmark-workloads/vectorsearch/params/filters/efficient/knowhere-diskann-relaxed-50k.json   --test-procedure no-train-test   --kill-running-processes

/root/.local/bin/opensearch-benchmark execute-test   --pipeline benchmark-only   --target-hosts 127.0.0.1:9200   --workload-path /tmp/opensearch-benchmark-workloads/vectorsearch   --workload-params /tmp/opensearch-benchmark-workloads/vectorsearch/params/filters/efficient/knowhere-diskann-restrictive-50k.json   --test-procedure no-train-test   --kill-running-processes
```
