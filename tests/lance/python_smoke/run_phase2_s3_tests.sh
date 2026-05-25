#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# run_phase2_s3_tests.sh
#
# Phase 2 S3-Compatible Storage Smoke Test Runner
#
# This script runs S3 storage smoke tests for Phase 2 Lance data endpoints.
# Tests verify storage_options configuration, credential vending, and data operations
# with S3-compatible object storage (MinIO by default).
#
# Prerequisites:
# - MinIO or S3-compatible storage running at localhost:9000
# - UC server with Lance routes enabled
# - lance-test bucket created in S3 storage
#
# Usage:
#   ./run_phase2_s3_tests.sh                # Run S3 storage tests
#   ./run_phase2_s3_tests.sh --setup-minio  # Start MinIO and create bucket
#
# Environment Variables:
#   UC_HOST        - UC server host (default: http://localhost:8080)
#   UC_LANCE_URI   - UC Lance REST URI
#   S3_ENDPOINT    - S3 endpoint (default: http://localhost:9000)
#   S3_ACCESS_KEY  - S3 access key (default: admin)
#   S3_SECRET_KEY  - S3 secret key (default: password)
#   S3_BUCKET      - S3 bucket name (default: lance-test)
#
# Exit Codes:
#   0 - All tests passed (or tests skipped gracefully)
#   1 - Tests failed
#   2 - Environment setup failed
#   3 - UC server not reachable
#   4 - S3 storage not reachable

set -e

# ==============================================================================
# Configuration
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# UC server configuration
UC_HOST="${UC_HOST:-http://localhost:8080}"
UC_LANCE_URI="${UC_LANCE_URI:-${UC_HOST}/api/2.1/unity-catalog/lance}"

# S3/MinIO configuration
S3_ENDPOINT="${S3_ENDPOINT:-http://localhost:9000}"
S3_ACCESS_KEY="${S3_ACCESS_KEY:-admin}"
S3_SECRET_KEY="${S3_SECRET_KEY:-password}"
S3_BUCKET="${S3_BUCKET:-lance-test}"

# Test options
SETUP_MINIO=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --setup-minio)
            SETUP_MINIO=true
            shift
            ;;
        --help)
            echo "Usage: $0 [--setup-minio]"
            echo ""
            echo "Options:"
            echo "  --setup-minio    Start MinIO container and create test bucket"
            echo ""
            echo "Environment Variables:"
            echo "  UC_HOST          UC server host (default: http://localhost:8080)"
            echo "  UC_LANCE_URI     UC Lance REST URI"
            echo "  S3_ENDPOINT      S3 endpoint (default: http://localhost:9000)"
            echo "  S3_ACCESS_KEY    S3 access key (default: admin)"
            echo "  S3_SECRET_KEY    S3 secret key (default: password)"
            echo "  S3_BUCKET        S3 bucket name (default: lance-test)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ==============================================================================
# MinIO Setup (if requested)
# ==============================================================================

if [[ "$SETUP_MINIO" == "true" ]]; then
    echo "=== Setting up MinIO ==="
    echo ""

    # Check if MinIO container exists
    MINIO_CONTAINER="iceberg-minio"

    if docker ps -a --format '{{.Names}}' | grep -q "$MINIO_CONTAINER"; then
        echo "MinIO container exists: $MINIO_CONTAINER"

        # Start container if not running
        if ! docker ps --format '{{.Names}}' | grep -q "$MINIO_CONTAINER"; then
            echo "Starting MinIO container..."
            docker start "$MINIO_CONTAINER"
            sleep 3
        fi
    else
        echo "Creating MinIO container..."
        docker run -d \
            --name "$MINIO_CONTAINER" \
            -p 9000:9000 \
            -p 9001:9001 \
            -e MINIO_ROOT_USER="$S3_ACCESS_KEY" \
            -e MINIO_ROOT_PASSWORD="$S3_SECRET_KEY" \
            -v /home/lei/iceberg-data/warehouse/minio_data:/data \
            minio/minio server /data --console-address ":9001"
        sleep 5
    fi

    echo "MinIO is running at $S3_ENDPOINT"
    echo ""

    # Create test bucket
    echo "Creating test bucket: $S3_BUCKET"
    pip install minio --break-system-packages -q 2>/dev/null || pip3 install minio -q

    python3 << EOF
from minio import Minio
client = Minio(
    "localhost:9000",
    access_key="$S3_ACCESS_KEY",
    secret_key="$S3_SECRET_KEY",
    secure=False
)
if not client.bucket_exists("$S3_BUCKET"):
    client.make_bucket("$S3_BUCKET")
    print(f"Created bucket: $S3_BUCKET")
else:
    print(f"Bucket already exists: $S3_BUCKET")
EOF

    echo ""
fi

# ==============================================================================
# Environment Checks
# ==============================================================================

echo "=== Phase 2 S3 Storage Smoke Tests ==="
echo ""
echo "Configuration:"
echo "  UC_HOST:        $UC_HOST"
echo "  UC_LANCE_URI:   $UC_LANCE_URI"
echo "  S3_ENDPOINT:    $S3_ENDPOINT"
echo "  S3_BUCKET:      $S3_BUCKET"
echo ""

# Check Python
echo "Checking Python environment..."
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python3 not available"
    exit 2
fi

PYTHON_VERSION=$(python3 --version)
echo "  Python: $PYTHON_VERSION"

# Install dependencies
echo ""
echo "Installing test dependencies..."
pip install -q --break-system-packages requests pytest minio 2>/dev/null || pip3 install -q --break-system-packages requests pytest minio

# ==============================================================================
# UC Server Check
# ==============================================================================

echo ""
echo "Checking UC server availability..."

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$UC_HOST/api/2.1/unity-catalog/catalogs" 2>/dev/null || echo "000")

if [[ "$HTTP_STATUS" == "200" ]]; then
    echo "  UC server: AVAILABLE (status $HTTP_STATUS)"
elif [[ "$HTTP_STATUS" == "401" ]]; then
    echo "  UC server: AVAILABLE but requires authentication (status $HTTP_STATUS)"
    echo "  Note: Tests will proceed but may need auth configuration"
else
    echo "  WARNING: UC server may not be running (status $HTTP_STATUS)"
    echo "  Tests will proceed but may fail if server is unavailable"
fi

# ==============================================================================
# S3 Storage Check
# ==============================================================================

echo ""
echo "Checking S3 storage availability..."

S3_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$S3_ENDPOINT/minio/health/live" 2>/dev/null || echo "000")

if [[ "$S3_HEALTH" == "200" ]]; then
    echo "  S3 storage: AVAILABLE (status $S3_HEALTH)"
elif [[ "$S3_HEALTH" == "000" ]]; then
    echo "  S3 storage: NOT reachable (status $S3_HEALTH)"
    echo ""
    echo "  To start MinIO:"
    echo "    ./run_phase2_s3_tests.sh --setup-minio"
    echo ""
    echo "  Or manually:"
    echo "    docker start iceberg-minio"
    echo ""
    exit 4
else
    echo "  S3 storage: AVAILABLE but may need setup (status $S3_HEALTH)"
fi

# ==============================================================================
# Run S3 Storage Tests
# ==============================================================================

echo ""
echo "=== Running Phase 2 S3 Storage Smoke Tests ==="
echo ""

# Set environment variables for tests
export UC_HOST
export UC_LANCE_URI
export S3_ENDPOINT
export S3_ACCESS_KEY
export S3_SECRET_KEY
export S3_BUCKET

S3_EXIT_CODE=0

python3 -m pytest phase2_s3_storage_smoke.py \
    -v \
    --tb=short \
    --color=yes \
    -m smoke \
    || S3_EXIT_CODE=$?

# ==============================================================================
# Test Summary
# ==============================================================================

echo ""
echo "=== Test Summary ==="
echo ""

if [[ $S3_EXIT_CODE -eq 0 ]]; then
    echo "S3 Storage tests: PASSED"
    echo ""
    echo "Phase 2 S3-compatible storage smoke tests completed successfully."
    exit 0
elif [[ $S3_EXIT_CODE -eq 5 ]]; then
    # pytest returns 5 when all tests are skipped
    echo "S3 Storage tests: SKIPPED"
    echo ""
    echo "Tests were skipped due to missing environment or dependencies."
    echo ""
    echo "Possible reasons:"
    echo "  - Lance execution backend not configured"
    echo "  - UC server requires authentication"
    echo "  - MinIO client not available"
    exit 0
else
    echo "S3 Storage tests: FAILED (exit code $S3_EXIT_CODE)"
    echo ""
    echo "Please check:"
    echo "  1. UC server is running at $UC_HOST"
    echo "  2. Lance execution backend is configured"
    echo "  3. MinIO is running at $S3_ENDPOINT"
    echo "  4. Test bucket '$S3_BUCKET' exists"
    exit 1
fi