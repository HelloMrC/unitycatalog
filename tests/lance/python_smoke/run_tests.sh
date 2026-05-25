#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# run_tests.sh
#
# Phase 1 Lance Client Smoke Test Runner
#
# This script runs both raw HTTP tests (required) and native client tests (optional).
# Raw HTTP tests are the primary validation mechanism for Phase 1 endpoints.
# Native client tests validate LanceDB SDK compatibility and will be skipped
# gracefully if the SDK or REST namespace client is unavailable.
#
# Usage:
#   ./run_tests.sh                    # Run all tests
#   ./run_tests.sh --raw-http-only    # Run only raw HTTP tests
#   ./run_tests.sh --native-only      # Run only native client tests
#
# Environment Variables:
#   UC_HOST        - UC server host (default: http://localhost:8080)
#   UC_LANCE_URI   - UC Lance REST URI (default: ${UC_HOST}/api/2.1/unity-catalog/lance)
#
# Exit Codes:
#   0 - All tests passed (or native tests skipped gracefully)
#   1 - Raw HTTP tests failed (required tests)
#   2 - Environment setup failed
#   3 - UC server not reachable

set -e

# ==============================================================================
# Configuration
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# UC server configuration (can be overridden by environment variables)
UC_HOST="${UC_HOST:-http://localhost:8080}"
UC_LANCE_URI="${UC_LANCE_URI:-${UC_HOST}/api/2.1/unity-catalog/lance}"

# Test options
RAW_HTTP_ONLY=false
NATIVE_ONLY=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --raw-http-only)
            RAW_HTTP_ONLY=true
            shift
            ;;
        --native-only)
            NATIVE_ONLY=true
            shift
            ;;
        --help)
            echo "Usage: $0 [--raw-http-only] [--native-only]"
            echo ""
            echo "Options:"
            echo "  --raw-http-only    Run only raw HTTP tests (required)"
            echo "  --native-only      Run only native client tests (optional)"
            echo ""
            echo "Environment Variables:"
            echo "  UC_HOST            UC server host (default: http://localhost:8080)"
            echo "  UC_LANCE_URI       UC Lance REST URI"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ==============================================================================
# Environment Setup
# ==============================================================================

echo "=== Phase 1 Lance Client Smoke Tests ==="
echo ""
echo "Configuration:"
echo "  UC_HOST:        $UC_HOST"
echo "  UC_LANCE_URI:   $UC_LANCE_URI"
echo ""

# Check Python availability
echo "Checking Python environment..."
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python3 not available"
    exit 2
fi

PYTHON_VERSION=$(python3 --version)
echo "  Python: $PYTHON_VERSION"

# Install test dependencies
echo ""
echo "Installing test dependencies..."
pip install -q -r requirements.txt 2>/dev/null || pip3 install -q -r requirements.txt

# Verify required packages for raw HTTP tests
echo ""
echo "Verifying required packages (raw HTTP):"
python3 -c "import requests; print(f'  requests: {requests.__version__}')" || {
    echo "ERROR: requests package not available"
    exit 2
}
python3 -c "import pytest; print(f'  pytest: {pytest.__version__}')" || {
    echo "ERROR: pytest package not available"
    exit 2
}

# ==============================================================================
# UC Server Availability Check
# ==============================================================================

echo ""
echo "Checking UC server availability..."

# Check if UC server is running
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$UC_HOST/api/2.1/unity-catalog/catalogs" 2>/dev/null || echo "000")

if [[ "$HTTP_STATUS" == "200" ]]; then
    echo "  UC server: AVAILABLE (status $HTTP_STATUS)"
elif [[ "$HTTP_STATUS" == "401" ]]; then
    echo "  UC server: AVAILABLE but requires authentication (status $HTTP_STATUS)"
    echo "  Note: Tests will proceed but may need auth configuration"
else
    echo "  WARNING: UC server may not be running (status $HTTP_STATUS)"
    echo "  Tests will proceed but may fail if server is unavailable"
    echo ""
    echo "  To start UC server, run:"
    echo "    cd /home/lei/data_ai/learning/codebase/unitycatalog"
    echo "    ./build/sbt server/run"
    echo ""
fi

# ==============================================================================
# Raw HTTP Tests (Required)
# ==============================================================================

RAW_HTTP_EXIT_CODE=0

if [[ "$NATIVE_ONLY" != "true" ]]; then
    echo ""
    echo "=== Running Raw HTTP Smoke Tests (Required) ==="
    echo ""
    echo "These tests verify UC Lance REST endpoint behavior using raw HTTP."
    echo "They are REQUIRED for Phase 1 validation."
    echo ""

    # Set environment variables for tests
    export UC_HOST
    export UC_LANCE_URI

    # Run raw HTTP tests
    python3 -m pytest phase1_raw_http_smoke.py \
        -v \
        --tb=short \
        --color=yes \
        -x \
        || RAW_HTTP_EXIT_CODE=$?

    if [[ $RAW_HTTP_EXIT_CODE -eq 0 ]]; then
        echo ""
        echo "Raw HTTP tests: PASSED"
    else
        echo ""
        echo "Raw HTTP tests: FAILED (exit code $RAW_HTTP_EXIT_CODE)"
        echo ""
        echo "Raw HTTP tests are REQUIRED for Phase 1 validation."
        echo "Please check:"
        echo "  1. UC server is running at $UC_HOST"
        echo "  2. Lance REST endpoint is mounted at $UC_LANCE_URI"
        echo "  3. Test output above for specific failures"
    fi
fi

# ==============================================================================
# Native Client Tests (Optional)
# ==============================================================================

NATIVE_EXIT_CODE=0

if [[ "$RAW_HTTP_ONLY" != "true" ]]; then
    echo ""
    echo "=== Checking Native Client Availability ==="

    # Check native client packages
    NATIVE_AVAILABLE=true

    python3 -c "import lancedb; print(f'  lancedb: {lancedb.__version__}')" 2>/dev/null || {
        echo "  lancedb: NOT installed"
        NATIVE_AVAILABLE=false
    }

    python3 -c "import lance_namespace; print('  lance-namespace: available')" 2>/dev/null || {
        echo "  lance-namespace: NOT installed"
        NATIVE_AVAILABLE=false
    }

    python3 -c "import pyarrow; print(f'  pyarrow: {pyarrow.__version__}')" 2>/dev/null || {
        echo "  pyarrow: NOT installed"
    }

    echo ""
    echo "=== Running Native Client Smoke Tests (Optional) ==="
    echo ""

    if [[ "$NATIVE_AVAILABLE" == "true" ]]; then
        echo "Native client packages available. Running tests..."
        echo ""

        python3 -m pytest phase1_native_client_smoke.py \
            -v \
            --tb=short \
            --color=yes \
            || NATIVE_EXIT_CODE=$?

        if [[ $NATIVE_EXIT_CODE -eq 0 ]]; then
            echo ""
            echo "Native client tests: PASSED"
        else
            echo ""
            echo "Native client tests: FAILED or SKIPPED (exit code $NATIVE_EXIT_CODE)"
            echo ""
            echo "Native client tests are OPTIONAL. Failures/skips are acceptable."
            echo "Raw HTTP tests serve as the primary validation mechanism."
        fi
    else
        echo "Native client packages NOT available."
        echo "Skipping native client tests."
        echo ""
        echo "To enable native client tests, install:"
        echo "  pip install lancedb lance-namespace pyarrow"
        echo ""
        echo "Note: Native client tests are OPTIONAL. Raw HTTP tests are the primary validation."
    fi
fi

# ==============================================================================
# Test Summary
# ==============================================================================

echo ""
echo "=== Test Summary ==="
echo ""

if [[ "$RAW_HTTP_ONLY" == "true" ]]; then
    echo "Mode: Raw HTTP tests only"
    echo ""
    if [[ $RAW_HTTP_EXIT_CODE -eq 0 ]]; then
        echo "Result: PASSED"
        echo ""
        echo "Raw HTTP tests successfully validated UC Lance REST endpoint."
        exit 0
    else
        echo "Result: FAILED"
        echo ""
        echo "Raw HTTP tests are required for Phase 1 validation."
        exit 1
    fi
elif [[ "$NATIVE_ONLY" == "true" ]]; then
    echo "Mode: Native client tests only"
    echo ""
    if [[ $NATIVE_EXIT_CODE -eq 0 ]]; then
        echo "Result: PASSED or SKIPPED"
        exit 0
    else
        echo "Result: FAILED (acceptable for optional tests)"
        exit 0
    fi
else
    echo "Mode: All tests (Raw HTTP + Native Client)"
    echo ""

    if [[ $RAW_HTTP_EXIT_CODE -eq 0 ]]; then
        echo "Raw HTTP tests: PASSED (Required)"

        if [[ $NATIVE_EXIT_CODE -eq 0 ]]; then
            echo "Native client tests: PASSED (Optional)"
        else
            echo "Native client tests: FAILED/SKIPPED (Optional - acceptable)"
        fi

        echo ""
        echo "Overall result: PASSED"
        echo ""
        echo "Phase 1 Lance REST endpoint validation complete."
        exit 0
    else
        echo "Raw HTTP tests: FAILED (Required)"
        echo "Native client tests: SKIPPED (due to raw HTTP failure)"
        echo ""
        echo "Overall result: FAILED"
        echo ""
        echo "Raw HTTP tests are required. Please fix failures before proceeding."
        exit 1
    fi
fi