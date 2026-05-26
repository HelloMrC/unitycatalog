#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# run_phase2_spark_s3_write_tests.sh
#
# Phase 2 Spark S3 Write Smoke Test Runner
#
# Tests the write pattern: Spark/LanceDB writes to S3, registers metadata in UC.
# Rollback pattern: If UC registration fails, delete S3 data.
#
# Prerequisites:
# - PySpark installed (pip install pyspark)
# - lance-spark bundle JAR downloaded
# - UC server running with Lance routes enabled
# - MinIO running at localhost:9000
# - LanceDB installed (pip install lancedb)
# - MinIO Python client (pip install minio)
#
# Usage:
#   ./run_phase2_spark_s3_write_tests.sh
#
# Environment Variables:
#   UC_HOST         - UC server host
#   S3_ENDPOINT     - MinIO endpoint
#   S3_ACCESS_KEY   - MinIO access key
#   S3_SECRET_KEY   - MinIO secret key
#   S3_BUCKET       - S3 bucket name

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Default JAR path
SPARK_JAR_PATH="${SPARK_JAR_PATH:-$SCRIPT_DIR/../spark_jars/lance-spark-bundle.jar}"
export SPARK_JAR_PATH

echo "=== Phase 2 Spark S3 Write Smoke Tests ==="
echo ""
echo "Configuration:"
echo "  UC_HOST: ${UC_HOST:-http://localhost:8080}"
echo "  S3_ENDPOINT: ${S3_ENDPOINT:-http://localhost:9000}"
echo "  S3_BUCKET: ${S3_BUCKET:-lance-test}"
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

# Check MinIO client
echo "Checking MinIO Python client..."
if ! python3 -c "import minio; print(f'MinIO version: {minio.__version__}')" 2>/dev/null; then
    echo "MinIO client not installed. Install with:"
    echo "  pip install minio --break-system-packages"
    exit 2
fi

# Check UC server
echo ""
echo "Checking UC server..."
UC_HOST="${UC_HOST:-http://localhost:8080}"
if ! curl -s "${UC_HOST}/api/2.1/unity-catalog/lance/v1/namespace" > /dev/null 2>&1; then
    echo "WARNING: UC server may not be running at ${UC_HOST}"
    echo ""
    echo "Tests will be skipped if UC is unavailable."
fi

# Check MinIO
echo "Checking MinIO..."
S3_ENDPOINT="${S3_ENDPOINT:-http://localhost:9000}"
if ! curl -s "${S3_ENDPOINT}/minio/health/live" > /dev/null 2>&1; then
    echo "WARNING: MinIO may not be running at ${S3_ENDPOINT}"
    echo ""
    echo "Tests will be skipped if MinIO is unavailable."
fi

# Run tests
echo ""
echo "=== Running Spark S3 Write Tests ==="
echo ""

TEST_EXIT_CODE=0

python3 -m pytest phase2_spark_s3_write_smoke.py \
    -v \
    --tb=short \
    --color=yes \
    -m "not skip" \
    || TEST_EXIT_CODE=$?

# Summary
echo ""
echo "=== Test Summary ==="
echo ""

if [[ $TEST_EXIT_CODE -eq 0 ]]; then
    echo "Spark S3 Write tests: PASSED"
    echo ""
    echo "Phase 2 Spark S3 write smoke tests completed successfully."
elif [[ $TEST_EXIT_CODE -eq 5 ]]; then
    echo "Spark S3 Write tests: ALL SKIPPED"
    echo ""
    echo "Tests were skipped due to missing environment or dependencies."
else
    echo "Spark S3 Write tests: FAILED (exit code $TEST_EXIT_CODE)"
    echo ""
    echo "Check the output above for details."
fi

exit $TEST_EXIT_CODE