#!/bin/bash

# Utility script for building and running k-NN tests (Unit and Integration)
# Based on the verified environment configuration for this workspace.

set -e

# --- Environment Setup ---
# Automatically find the project root regardless of where the script is called from
PRJ_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PRJ_ROOT"

# Use the local JDK 21 installed in the project
export JAVA_HOME="$PRJ_ROOT/opt/jdk-21"
export JAVA21_HOME="$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# AVX2 is mandatory for this environment to avoid UnsatisfiedLinkError
# AVX512 is disabled to prevent alignment issues confirmed during testing
AVX_FLAGS="-Davx2.enabled=true -Davx512.enabled=false"

# Performance and stability flags
# -Dnproc.count=4: Limits parallelism to prevent memory exhaustion
# -x spotlessCheck: Skips formatting checks for faster test cycles
GRADLE_FLAGS="-Dnproc.count=4 --no-daemon -x spotlessCheck"

# --- Main Logic ---
usage() {
    echo "Usage: $0 {build|ut|it} [test_name_pattern]"
    echo ""
    echo "Actions:"
    echo "  build             - Clean and build JNI libraries (AVX2 optimized)"
    echo "  ut                - Run all unit tests"
    echo "  ut <Pattern>      - Run specific unit test(s) (e.g. ./scripts/dev_tests.sh ut KNNQueryBuilderTests)"
    echo "  it                - Run all integration tests"
    echo "  it <Pattern>      - Run specific integration test(s) (e.g. ./scripts/dev_tests.sh it FaissIT)"
    echo ""
    exit 1
}

if [ "$#" -lt 1 ]; then
    usage
fi

ACTION=$1
PATTERN=$2

case "$ACTION" in
    build)
        echo "[INFO] Cleaning and building JNI libraries..."
        ./gradlew clean cmakeJniLib buildJniLib $AVX_FLAGS $GRADLE_FLAGS
        ;;
    ut)
        if [ -z "$PATTERN" ]; then
            echo "[INFO] Executing all unit tests..."
            ./gradlew test $AVX_FLAGS $GRADLE_FLAGS
        else
            echo "[INFO] Executing unit tests matching: $PATTERN..."
            ./gradlew test --tests "$PATTERN" $AVX_FLAGS $GRADLE_FLAGS
        fi
        ;;
    it)
        if [ -z "$PATTERN" ]; then
            echo "[INFO] Executing all integration tests..."
            ./gradlew :integTest $AVX_FLAGS $GRADLE_FLAGS
        else
            echo "[INFO] Executing integration tests matching: $PATTERN..."
            ./gradlew :integTest --tests "$PATTERN" $AVX_FLAGS $GRADLE_FLAGS
        fi
        ;;
    *)
        usage
        ;;
esac
