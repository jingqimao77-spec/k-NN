#!/usr/bin/env python3

import argparse
import inspect
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import h5py
import matplotlib.pyplot as plt
import numpy as np


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_WORKLOAD_SOURCE = Path("/tmp/opensearch-benchmark-workloads/vectorsearch")
DEFAULT_OUTPUT_DIR = Path("/tmp/knowhere-filter-bench/recall_matched")
DEFAULT_ENDPOINT = "http://127.0.0.1:9200"
OSB_BIN = Path("/root/.local/bin/opensearch-benchmark")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run recall-matched Faiss HNSW vs Knowhere DiskANN filter benchmarks and generate plots."
    )
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="OpenSearch endpoint, for example http://127.0.0.1:9200")
    parser.add_argument("--workload-source", type=Path, default=DEFAULT_WORKLOAD_SOURCE, help="Source vectorsearch workload path.")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR, help="Directory to store generated workload copy, results, and plots.")
    parser.add_argument("--target-recall", type=float, default=0.95, help="Target recall@k for relaxed-scenario matching.")
    parser.add_argument(
        "--search-clients", type=int, default=1, help="search_clients value used in generated param files."
    )
    return parser.parse_args()


@dataclass(frozen=True)
class EngineConfig:
    name: str
    label: str
    base_relaxed: Path
    base_restrictive: Path
    query_param_name: str
    candidates: Tuple[int, ...]
    color: str


ENGINES = (
    EngineConfig(
        name="faiss_hnsw",
        label="Faiss HNSW",
        base_relaxed=SCRIPT_DIR / "faiss-hnsw-relaxed-50k.json",
        base_restrictive=SCRIPT_DIR / "faiss-hnsw-restrictive-50k.json",
        query_param_name="ef_search",
        candidates=(64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096),
        color="#1f77b4",
    ),
    EngineConfig(
        name="knowhere_diskann_cache2",
        label="Knowhere DiskANN cache2",
        base_relaxed=SCRIPT_DIR / "knowhere-diskann-cache2-relaxed-50k.json",
        base_restrictive=SCRIPT_DIR / "knowhere-diskann-cache2-restrictive-50k.json",
        query_param_name="search_list",
        candidates=(64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192),
        color="#d62728",
    ),
)


RESULT_ROW_RE = re.compile(r"^\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|$")


def load_json(path: Path) -> Dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, payload: Dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=False)
        handle.write("\n")


def run_command(args: List[str], stdout_path: Path) -> None:
    stdout_path.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    stdout_path.write_text(process.stdout, encoding="utf-8")
    if process.returncode != 0:
        raise RuntimeError(
            f"Command failed with exit code {process.returncode}: {' '.join(args)}\nOutput saved to {stdout_path}"
        )


def patch_osbenchmark_params(output_dir: Path) -> Path:
    import osbenchmark.workload.params as params_module

    params_py = Path(inspect.getsourcefile(params_module)).resolve()
    backup_py = output_dir / "osbenchmark_params.py.bak"
    current = params_py.read_text(encoding="utf-8")
    backup_py.parent.mkdir(parents=True, exist_ok=True)
    if backup_py.exists():
        base_text = backup_py.read_text(encoding="utf-8")
    else:
        base_text = current
        backup_py.write_text(base_text, encoding="utf-8")

    signature_old = 'def _build_vector_search_query_body(self, vector, efficient_filter=None, filter_type=None, filter_body=None) -> dict:'
    signature_new = 'def _build_vector_search_query_body(self, vector, efficient_filter=None, filter_type=None, filter_body=None, query_method_parameters=None) -> dict:'
    if signature_old not in base_text:
        raise RuntimeError(f"Failed to find vector query builder signature in {params_py}")
    patched = base_text.replace(signature_old, signature_new, 1)

    query_old = """        query = {\n            \"vector\": vector,\n            \"k\": self.k,\n        }\n        if efficient_filter:\n"""
    query_new = """        query = {\n            \"vector\": vector,\n            \"k\": self.k,\n        }\n        if query_method_parameters:\n            query[\"method_parameters\"] = query_method_parameters\n        if efficient_filter:\n"""
    if query_old not in patched:
        raise RuntimeError(f"Failed to find vector query body block in {params_py}")
    patched = patched.replace(query_old, query_new, 1)

    update_old = """    def _update_body_params(self, vector):\n        # accept body params if passed from workload, else, create empty dictionary\n        body_params = self.query_params.get(self.PARAMS_NAME_BODY) or dict()\n        if self.PARAMS_NAME_SIZE not in body_params:\n            body_params[self.PARAMS_NAME_SIZE] = self.k\n        filter_type=self.query_params.get(self.PARAMS_NAME_FILTER_TYPE)\n        filter_body=self.query_params.get(self.PARAMS_NAME_FILTER_BODY)\n        efficient_filter = filter_body if filter_type == \"efficient\" else None\n\n        # override query params with vector search query\n        body_params[self.PARAMS_NAME_QUERY] = self._build_vector_search_query_body(vector, efficient_filter, filter_type, filter_body)\n\n        if filter_type == \"post_filter\":\n            body_params[\"post_filter\"] = filter_body\n\n        self.query_params.update({self.PARAMS_NAME_BODY: body_params})\n"""
    update_new = """    def _update_body_params(self, vector):\n        # accept body params if passed from workload, else, create empty dictionary\n        body_params = self.query_params.get(self.PARAMS_NAME_BODY) or dict()\n        query_method_parameters = body_params.pop(\"query_method_parameters\", None)\n        if self.PARAMS_NAME_SIZE not in body_params:\n            body_params[self.PARAMS_NAME_SIZE] = self.k\n        filter_type=self.query_params.get(self.PARAMS_NAME_FILTER_TYPE)\n        filter_body=self.query_params.get(self.PARAMS_NAME_FILTER_BODY)\n        efficient_filter = filter_body if filter_type == \"efficient\" else None\n\n        # override query params with vector search query\n        body_params[self.PARAMS_NAME_QUERY] = self._build_vector_search_query_body(\n            vector, efficient_filter, filter_type, filter_body, query_method_parameters\n        )\n\n        if filter_type == \"post_filter\":\n            body_params[\"post_filter\"] = filter_body\n\n        self.query_params.update({self.PARAMS_NAME_BODY: body_params})\n"""
    if update_old not in patched:
        raise RuntimeError(f"Failed to find body update block in {params_py}")
    patched = patched.replace(update_old, update_new, 1)

    params_py.write_text(patched, encoding="utf-8")
    return params_py

def http_index_exists(endpoint: str, index_name: str) -> bool:
    request = Request(f"{endpoint.rstrip('/')}/{index_name}", method="HEAD")
    try:
        with urlopen(request, timeout=10) as response:
            return 200 <= response.status < 300
    except HTTPError as error:
        if error.code == 404:
            return False
        raise
    except URLError as error:
        raise RuntimeError(f"Failed to reach OpenSearch endpoint {endpoint}: {error}") from error


def build_index_if_missing(endpoint: str, workload_path: Path, base_params_path: Path, logs_dir: Path) -> None:
    params = load_json(base_params_path)
    index_name = params["target_index_name"]
    if http_index_exists(endpoint, index_name):
        return

    results_file = logs_dir / f"build-{index_name}.md"
    stdout_file = logs_dir / f"build-{index_name}.log"
    args = [
        str(OSB_BIN),
        "run",
        "--pipeline",
        "benchmark-only",
        "--target-hosts",
        endpoint.replace("http://", "").replace("https://", ""),
        "--workload-path",
        str(workload_path),
        "--workload-params",
        str(base_params_path),
        "--test-procedure",
        "no-train-test",
        "--results-format",
        "markdown",
        "--results-file",
        str(results_file),
        "--offline",
    ]
    run_command(args, stdout_file)


def render_param_file(base_params_path: Path, output_path: Path, query_method_parameters: Dict[str, int], search_clients: int) -> Dict:
    params = load_json(base_params_path)
    query_body = params.get("query_body") or {}
    query_body["query_method_parameters"] = query_method_parameters
    params["query_body"] = query_body
    params["search_clients"] = search_clients
    write_json(output_path, params)
    return params


def parse_markdown_results(results_path: Path) -> List[Dict[str, str]]:
    rows: List[Dict[str, str]] = []
    for raw_line in results_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line.startswith("|"):
            continue
        match = RESULT_ROW_RE.match(line)
        if not match:
            continue
        metric, task, value, unit = [part.strip() for part in match.groups()]
        if metric == "Metric" or set(metric) == {"-"}:
            continue
        rows.append({"metric": metric, "task": task, "value": value, "unit": unit})
    return rows


def find_metric(rows: List[Dict[str, str]], metric: str, task: str) -> float:
    for row in rows:
        if row["metric"] == metric and row["task"] == task:
            return float(row["value"])
    raise KeyError(f"Metric not found: metric={metric} task={task}")


def parse_summary(results_path: Path) -> Dict[str, float]:
    rows = parse_markdown_results(results_path)
    return {
        "qps": find_metric(rows, "Mean Throughput", "prod-queries"),
        "p50_ms": find_metric(rows, "50th percentile latency", "prod-queries"),
        "p90_ms": find_metric(rows, "90th percentile latency", "prod-queries"),
        "p99_ms": find_metric(rows, "99th percentile latency", "prod-queries"),
        "recall": find_metric(rows, "Mean recall@k", "prod-queries"),
        "recall_at_1": find_metric(rows, "Mean recall@1", "prod-queries"),
    }


def run_search_only(endpoint: str, workload_path: Path, params_path: Path, results_path: Path, stdout_path: Path) -> Dict[str, float]:
    args = [
        str(OSB_BIN),
        "run",
        "--pipeline",
        "benchmark-only",
        "--target-hosts",
        endpoint.replace("http://", "").replace("https://", ""),
        "--workload-path",
        str(workload_path),
        "--workload-params",
        str(params_path),
        "--test-procedure",
        "search-only",
        "--results-format",
        "markdown",
        "--results-file",
        str(results_path),
        "--offline",
    ]
    run_command(args, stdout_path)
    return parse_summary(results_path)


def choose_closest_to_target(sweep_rows: List[Dict[str, float]], target_recall: float) -> Dict[str, float]:
    return min(
        sweep_rows,
        key=lambda row: (abs(row["recall"] - target_recall), -row["qps"]),
    )


def decode_attribute_bytes(values: np.ndarray) -> np.ndarray:
    return np.char.decode(values.astype("S16"), "utf-8")


def compute_selectivity(dataset_path: Path, kind: str) -> Tuple[int, float]:
    with h5py.File(dataset_path, "r") as handle:
        attributes = handle["attributes"][:]
    colors = decode_attribute_bytes(attributes[:, 0])
    tastes = decode_attribute_bytes(attributes[:, 1])
    ages = decode_attribute_bytes(attributes[:, 2]).astype(np.int32)

    if kind == "relaxed":
        mask = ((30 <= ages) & (ages <= 70)) | np.isin(colors, ("green", "blue", "yellow")) | (tastes == "sweet")
    else:
        mask = ((30 <= ages) & (ages <= 60)) & (tastes == "bitter") & np.isin(colors, ("green", "blue"))

    selected_count = int(mask.sum())
    selectivity = float(selected_count / len(mask))
    return selected_count, selectivity


def plot_qps_recall(sweep_results: Dict[str, List[Dict[str, float]]], selected_rows: Dict[str, Dict[str, float]], output_path: Path) -> None:
    plt.figure(figsize=(8.5, 6.0))
    for engine in ENGINES:
        rows = sweep_results[engine.name]
        rows = sorted(rows, key=lambda row: row["recall"])
        x_values = [row["recall"] for row in rows]
        y_values = [row["qps"] for row in rows]
        plt.plot(x_values, y_values, marker="o", color=engine.color, label=engine.label)
        selected = selected_rows[engine.name]
        plt.scatter([selected["recall"]], [selected["qps"]], color=engine.color, s=120, edgecolors="black", zorder=5)
        plt.annotate(
            f'{engine.query_param_name}={selected["param_value"]}',
            (selected["recall"], selected["qps"]),
            textcoords="offset points",
            xytext=(6, 6),
            fontsize=9,
        )

    plt.xlabel("Recall@100")
    plt.ylabel("QPS")
    plt.title("Relaxed Filter Sweep: QPS vs Recall")
    plt.grid(True, linestyle="--", linewidth=0.5, alpha=0.5)
    plt.legend()
    plt.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_path, dpi=180)
    plt.close()


def plot_filter_ratio(fixed_results: Dict[str, Dict[str, Dict[str, float]]], selectivities: Dict[str, float], output_path: Path) -> None:
    plt.figure(figsize=(8.5, 6.0))
    for engine in ENGINES:
        xs = [selectivities["restrictive"], selectivities["relaxed"]]
        ys = [fixed_results[engine.name]["restrictive"]["qps"], fixed_results[engine.name]["relaxed"]["qps"]]
        plt.plot(xs, ys, marker="o", color=engine.color, label=engine.label)
        for scenario in ("restrictive", "relaxed"):
            row = fixed_results[engine.name][scenario]
            plt.annotate(
                f'{scenario}\nrecall={row["recall"]:.3f}',
                (selectivities[scenario], row["qps"]),
                textcoords="offset points",
                xytext=(6, 6),
                fontsize=9,
            )

    plt.xlabel("Filter Selectivity (allowed ratio)")
    plt.ylabel("QPS")
    plt.title("Fixed Near-95%-Recall Params: QPS vs Filter Selectivity")
    plt.grid(True, linestyle="--", linewidth=0.5, alpha=0.5)
    plt.legend()
    plt.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_path, dpi=180)
    plt.close()


def write_summary(
    output_path: Path,
    target_recall: float,
    sweep_results: Dict[str, List[Dict[str, float]]],
    selected_rows: Dict[str, Dict[str, float]],
    fixed_results: Dict[str, Dict[str, Dict[str, float]]],
    selectivities: Dict[str, float],
) -> None:
    lines = []
    lines.append("# Recall-Matched Filter Benchmark Summary")
    lines.append("")
    lines.append(f"- Target recall on relaxed scenario: {target_recall:.2f}")
    lines.append("- Knowhere uses the cache2 index template so the DiskANN cached-node path stays enabled.")
    lines.append("- Matching is done with query-time parameters only: `ef_search` for Faiss HNSW and `search_list` for Knowhere DiskANN.")
    lines.append("")

    lines.append("## Selected Parameters")
    lines.append("")
    for engine in ENGINES:
        selected = selected_rows[engine.name]
        lines.append(
            f"- {engine.label}: `{engine.query_param_name}={selected['param_value']}` "
            f"(relaxed recall={selected['recall']:.3f}, qps={selected['qps']:.2f})"
        )
    lines.append("")

    lines.append("## Filter Selectivity")
    lines.append("")
    for scenario in ("relaxed", "restrictive"):
        lines.append(
            f"- {scenario}: selected={int(selectivities[f'{scenario}_count'])}, "
            f"ratio={selectivities[scenario]:.4f}"
        )
    lines.append("")

    lines.append("## Fixed-Parameter Results")
    lines.append("")
    lines.append("| Engine | Scenario | Selectivity | QPS | p50 ms | p90 ms | p99 ms | recall@100 | recall@1 |")
    lines.append("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    for engine in ENGINES:
        for scenario in ("relaxed", "restrictive"):
            row = fixed_results[engine.name][scenario]
            lines.append(
                f"| {engine.label} | {scenario} | {selectivities[scenario]:.4f} | {row['qps']:.2f} | "
                f"{row['p50_ms']:.3f} | {row['p90_ms']:.3f} | {row['p99_ms']:.3f} | "
                f"{row['recall']:.3f} | {row['recall_at_1']:.3f} |"
            )
    lines.append("")

    lines.append("## Relaxed Sweep")
    lines.append("")
    lines.append("| Engine | Param | Recall@100 | QPS | p50 ms |")
    lines.append("|---|---:|---:|---:|---:|")
    for engine in ENGINES:
        for row in sweep_results[engine.name]:
            lines.append(
                f"| {engine.label} | {int(row['param_value'])} | {row['recall']:.3f} | {row['qps']:.2f} | {row['p50_ms']:.3f} |"
            )
    lines.append("")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    args = parse_args()
    output_dir = args.output_dir.resolve()
    patch_osbenchmark_params(output_dir)
    workload_path = args.workload_source.resolve()
    generated_params_dir = output_dir / "params"
    results_dir = output_dir / "results"
    plots_dir = output_dir / "plots"
    logs_dir = output_dir / "logs"
    for path in (output_dir, generated_params_dir, results_dir, plots_dir, logs_dir):
        path.mkdir(parents=True, exist_ok=True)

    for engine in ENGINES:
        build_index_if_missing(args.endpoint, workload_path, engine.base_relaxed, logs_dir)
        build_index_if_missing(args.endpoint, workload_path, engine.base_restrictive, logs_dir)

    selectivities: Dict[str, float] = {}
    for scenario, sample_file in (
        ("relaxed", SCRIPT_DIR / "knowhere-diskann-cache2-relaxed-50k.json"),
        ("restrictive", SCRIPT_DIR / "knowhere-diskann-cache2-restrictive-50k.json"),
    ):
        params = load_json(sample_file)
        dataset_path = Path(params["query_data_set_path"])
        selected_count, ratio = compute_selectivity(dataset_path, scenario)
        selectivities[scenario] = ratio
        selectivities[f"{scenario}_count"] = selected_count

    sweep_results: Dict[str, List[Dict[str, float]]] = {}
    selected_rows: Dict[str, Dict[str, float]] = {}
    fixed_results: Dict[str, Dict[str, Dict[str, float]]] = {}

    for engine in ENGINES:
        sweep_rows: List[Dict[str, float]] = []
        for candidate in engine.candidates:
            param_path = generated_params_dir / f"{engine.name}-relaxed-{engine.query_param_name}-{candidate}.json"
            results_path = results_dir / f"{engine.name}-relaxed-{engine.query_param_name}-{candidate}.md"
            stdout_path = logs_dir / f"{engine.name}-relaxed-{engine.query_param_name}-{candidate}.log"
            render_param_file(
                engine.base_relaxed,
                param_path,
                {engine.query_param_name: candidate},
                args.search_clients,
            )
            summary = run_search_only(args.endpoint, workload_path, param_path, results_path, stdout_path)
            summary["param_value"] = candidate
            sweep_rows.append(summary)

        sweep_results[engine.name] = sweep_rows
        selected_rows[engine.name] = choose_closest_to_target(sweep_rows, args.target_recall)

        fixed_results[engine.name] = {}
        fixed_param_value = int(selected_rows[engine.name]["param_value"])
        for scenario, base_path in (("relaxed", engine.base_relaxed), ("restrictive", engine.base_restrictive)):
            param_path = generated_params_dir / f"{engine.name}-{scenario}-fixed.json"
            results_path = results_dir / f"{engine.name}-{scenario}-fixed.md"
            stdout_path = logs_dir / f"{engine.name}-{scenario}-fixed.log"
            render_param_file(
                base_path,
                param_path,
                {engine.query_param_name: fixed_param_value},
                args.search_clients,
            )
            summary = run_search_only(args.endpoint, workload_path, param_path, results_path, stdout_path)
            summary["param_value"] = fixed_param_value
            fixed_results[engine.name][scenario] = summary

    plot_qps_recall(sweep_results, selected_rows, plots_dir / "relaxed_qps_vs_recall.png")
    plot_filter_ratio(fixed_results, selectivities, plots_dir / "filter_selectivity_vs_qps.png")
    write_summary(
        output_dir / "summary.md",
        args.target_recall,
        sweep_results,
        selected_rows,
        fixed_results,
        selectivities,
    )

    machine_summary = {
        "target_recall": args.target_recall,
        "selected_parameters": {
            engine.name: {
                "name": engine.query_param_name,
                "value": int(selected_rows[engine.name]["param_value"]),
            }
            for engine in ENGINES
        },
        "selectivities": selectivities,
        "sweep_results": sweep_results,
        "fixed_results": fixed_results,
        "plots": {
            "qps_vs_recall": str((plots_dir / "relaxed_qps_vs_recall.png").resolve()),
            "filter_selectivity_vs_qps": str((plots_dir / "filter_selectivity_vs_qps.png").resolve()),
        },
        "summary_markdown": str((output_dir / "summary.md").resolve()),
    }
    write_json(output_dir / "summary.json", machine_summary)

    print(json.dumps(machine_summary, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # pragma: no cover - best-effort script
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
