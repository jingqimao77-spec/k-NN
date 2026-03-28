#!/usr/bin/env python3

import argparse
from pathlib import Path

import h5py
import numpy as np


COLORS = ("red", "green", "blue", "yellow", "black")
TASTES = ("sweet", "bitter", "sour", "umami")


def parse_args():
    parser = argparse.ArgumentParser(description="Generate a small HDF5 dataset for OSB filter benchmarks.")
    parser.add_argument("--output", required=True, help="Output HDF5 path.")
    parser.add_argument(
        "--kind",
        required=True,
        choices=("relaxed", "restrictive"),
        help="Dataset flavor. Controls attribute selectivity and filtered ground truth for the official OSB filter bodies.",
    )
    parser.add_argument("--size", type=int, default=50000, help="Number of indexed vectors.")
    parser.add_argument("--queries", type=int, default=100, help="Number of query vectors.")
    parser.add_argument("--dimension", type=int, default=128, help="Vector dimension.")
    parser.add_argument("--k", type=int, default=100, help="Ground-truth neighbor count.")
    parser.add_argument("--seed", type=int, default=7, help="Random seed.")
    return parser.parse_args()


def sample_ages(rng, size, probability_in_range, low, high, floor=18, ceil=80):
    in_range = rng.random(size) < probability_in_range
    ages = np.empty(size, dtype=np.int32)
    ages[in_range] = rng.integers(low, high + 1, in_range.sum())
    out_count = (~in_range).sum()
    lower_count = out_count // 2
    upper_count = out_count - lower_count
    outside = []
    if lower_count:
        outside.append(rng.integers(floor, low, lower_count))
    if upper_count:
        outside.append(rng.integers(high + 1, ceil + 1, upper_count))
    if outside:
        outside_values = np.concatenate(outside)
        rng.shuffle(outside_values)
        ages[~in_range] = outside_values
    return ages


def build_attributes(rng, size, kind):
    if kind == "relaxed":
        colors = rng.choice(COLORS, size=size, p=(0.15, 0.22, 0.21, 0.17, 0.25))
        tastes = rng.choice(TASTES, size=size, p=(0.28, 0.22, 0.25, 0.25))
        ages = sample_ages(rng, size, probability_in_range=0.62, low=30, high=70)
    else:
        colors = rng.choice(COLORS, size=size, p=(0.45, 0.18, 0.16, 0.11, 0.10))
        tastes = rng.choice(TASTES, size=size, p=(0.26, 0.20, 0.27, 0.27))
        ages = sample_ages(rng, size, probability_in_range=0.38, low=30, high=60)

    attributes = np.empty((size, 3), dtype="S16")
    attributes[:, 0] = colors.astype("S16")
    attributes[:, 1] = tastes.astype("S16")
    attributes[:, 2] = ages.astype(str).astype("S16")
    return attributes, colors, tastes, ages


def build_filter_mask(kind, colors, tastes, ages):
    relaxed_mask = (
        ((30 <= ages) & (ages <= 70))
        | np.isin(colors, ("green", "blue", "yellow"))
        | (tastes == "sweet")
    )
    restrictive_mask = (
        ((30 <= ages) & (ages <= 60))
        & (tastes == "bitter")
        & np.isin(colors, ("green", "blue"))
    )
    return relaxed_mask if kind == "relaxed" else restrictive_mask


def compute_ground_truth(train, test, k, allowed_indices, batch_size=16):
    candidate_train = train[allowed_indices]
    candidate_norms = np.sum(candidate_train * candidate_train, axis=1)
    neighbors = np.empty((test.shape[0], k), dtype=np.int32)
    for start in range(0, test.shape[0], batch_size):
        end = min(start + batch_size, test.shape[0])
        query_batch = test[start:end]
        distances = (
            np.sum(query_batch * query_batch, axis=1)[:, None]
            + candidate_norms[None, :]
            - 2.0 * np.matmul(query_batch, candidate_train.T)
        )
        partition = np.argpartition(distances, kth=k - 1, axis=1)[:, :k]
        partition_distances = np.take_along_axis(distances, partition, axis=1)
        ordering = np.argsort(partition_distances, axis=1)
        candidate_neighbors = np.take_along_axis(partition, ordering, axis=1)
        neighbors[start:end] = allowed_indices[candidate_neighbors]
    return neighbors


def main():
    args = parse_args()
    if args.k > args.size:
        raise ValueError("k must be less than or equal to size")

    rng = np.random.default_rng(args.seed)
    train = rng.normal(loc=0.0, scale=1.0, size=(args.size, args.dimension)).astype(np.float32)
    query_source = rng.integers(0, args.size, size=args.queries)
    noise = rng.normal(loc=0.0, scale=0.01, size=(args.queries, args.dimension)).astype(np.float32)
    test = (train[query_source] + noise).astype(np.float32)
    attributes, colors, tastes, ages = build_attributes(rng, args.size, args.kind)
    filter_mask = build_filter_mask(args.kind, colors, tastes, ages)
    allowed_indices = np.flatnonzero(filter_mask).astype(np.int32)
    if allowed_indices.size < args.k:
        raise ValueError(
            f"Filtered candidate count {allowed_indices.size} is smaller than k={args.k}; increase dataset size or relax selectivity."
        )
    neighbors = compute_ground_truth(train, test, args.k, allowed_indices)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with h5py.File(output_path, "w") as handle:
        handle.create_dataset("train", data=train, compression="gzip")
        handle.create_dataset("test", data=test, compression="gzip")
        handle.create_dataset("neighbors", data=neighbors, compression="gzip")
        handle.create_dataset("attributes", data=attributes, compression="gzip")

    print(f"wrote={output_path}")
    print(f"kind={args.kind}")
    print(f"size={args.size}")
    print(f"queries={args.queries}")
    print(f"dimension={args.dimension}")
    print(f"k={args.k}")
    print(f"selected_count={allowed_indices.size}")
    print(f"selectivity={allowed_indices.size / args.size:.4f}")


if __name__ == "__main__":
    main()
