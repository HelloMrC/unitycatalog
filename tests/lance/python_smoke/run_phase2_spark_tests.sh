#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# run_phase2_spark_tests.sh
#
# Phase 2 Spark Connector Smoke Test Runner
#
# Prerequisites:
# - PySpark installed (pip install pyspark)
# - lance-spark bundle JAR downloaded
# - UC server running (optional, for UC integration tests)
# - MinIO running (optional, for S3 tests)
#
# Usage:
#   ./run_phase2_spark_tests.sh
#
# Environment Variables:
#   SPARK_JAR_PATH  - Path to lance-spark bundle JAR
#   UC_HOST         - UC server host
#   S3_ENDPOINT     - S3/MinIO endpoint

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Default JAR path
SPARK_JAR_PATH="${SPARK_JAR_PATH:-$SCRIPT_DIR/../spark_jars/lance-spark-bundle.jar}"
export SPARK_JAR_PATH

echo "=== Phase 2 Spark Connector Smoke Tests ==="
echo ""
echo "Configuration:"
echo "  SPARK_JAR_PATH: $SPARK_JAR_PATH"
echo ""

# Check JAR
if [[ ! -f "$SPARK_JAR_PATH" ]]; then
    echo "WARNING: lance-spark JAR not found at $SPARK_JAR_PATH"
    echo ""
    echo "Download from Aliyun mirror:"
    echo "  mkdir -p tests/lance/spark_jars"
    echo "  curl -L 'https://maven.aliyun.com/repository/public/com/lancedb/lance-spark-bundle-4.0_2.13/0.0.15/lance-spark-bundle-4.0_2.13-0.0.15.jar' -o tests/lance/spark_jars/lance-spark-bundle.jar"
    echo ""
    exit 2
fi

JAR_SIZE=$(du -m "$SPARK_JAR_PATH" | cut -f1)
echo "JAR size: ${JAR_SIZE} MB"

# Check PySpark
echo ""
echo "Checking PySpark..."
if ! python3 -c "import pyspark; print(f'PySpark version: {pyspark.__version__}')" 2>/dev/null; then
    echo "PySpark not installed. Install with:"
    echo "  pip install pyspark --break-system-packages"
    exit 2
fi

# Check LanceDB
echo "Checking LanceDB..."
if ! python3 -c "import lancedb; print(f'LanceDB version: {lancedb.__version__}')" 2>/dev/null; then
    echo "LanceDB not installed. Install with:"
    echo "  pip install lancedb --break-system-packages"
    exit 2
fi

# Run tests
echo ""
echo "=== Running Spark Smoke Tests ==="
echo ""

SPARK_EXIT_CODE=0

python3 -m pytest phase2_spark_smoke.py \
    -v \
    --tb=short \
    --color=yes \
    -m "not skip" \
    || SPARK_EXIT_CODE=$?

# Summary
echo ""
echo "=== Test Summary ==="
echo ""

if [[ $SPARK_EXIT_CODE -eq 0 ]]; then
    echo "Spark tests: PASSED"
    echo ""
    echo "Phase 2 Spark connector smoke tests completed successfully."
elif [[ $SPARK_EXIT_CODE -eq 5 ]]; then
    echo "Spark tests: ALL SKIPPED"
    echo ""
    echo "Tests were skipped due to missing environment or dependencies."
else
    echo "Spark tests: FAILED (exit code $SPARK_EXIT_CODE)"
    echo ""
    echo "Check the output above for details."
fi

exit $SPARK_EXIT_CODE