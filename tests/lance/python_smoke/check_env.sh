#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# check_env.sh
#
# Phase 1 Lance Client Smoke Test Environment Checker
#
# This script checks the environment setup for running Phase 1 native client
# smoke tests. It verifies:
# - Python version and availability
# - Required packages for raw HTTP tests
# - Optional packages for native client tests
# - UC server availability
# - Lance REST endpoint accessibility
#
# Usage:
#   ./check_env.sh
#
# Exit Codes:
#   0 - Environment is ready for testing
#   1 - Required packages missing
#   2 - UC server not reachable

set -e

# ==============================================================================
# Configuration
# ==============================================================================

UC_HOST="${UC_HOST:-http://localhost:8080}"
UC_LANCE_URI="${UC_LANCE_URI:-${UC_HOST}/api/2.1/unity-catalog/lance}"

# ==============================================================================
# Environment Checks
# ==============================================================================

echo "=== Environment Check for Phase 1 Lance Client Smoke Tests ==="
echo ""

# -----------------------------------------------------------------------------
# Python Version
# -----------------------------------------------------------------------------
echo "Python Environment:"
echo ""

if command -v python3 &> /dev/null; then
    PYTHON_VERSION=$(python3 --version)
    PYTHON_PATH=$(which python3)
    echo "  ✓ Python available"
    echo "    Version: $PYTHON_VERSION"
    echo "    Path:    $PYTHON_PATH"
else
    echo "  ✗ Python NOT available"
    echo "    Please install Python 3.8+ to run smoke tests"
    exit 1
fi

# -----------------------------------------------------------------------------
# Required Packages (Raw HTTP Tests)
# -----------------------------------------------------------------------------
echo ""
echo "Required Packages (Raw HTTP Tests):"
echo ""

# These packages are mandatory for raw HTTP smoke tests
# which are the primary validation mechanism for Phase 1

REQUIRED_PACKAGES="requests pytest"
REQUIRED_OK=true

for pkg in $REQUIRED_PACKAGES; do
    if python3 -c "import $pkg" 2>/dev/null; then
        VERSION=$(python3 -c "import $pkg; print($pkg.__version__ if hasattr($pkg, '__version__') else 'available')" 2>/dev/null || echo "available")
        echo "  ✓ $pkg: $VERSION"
    else
        echo "  ✗ $pkg: NOT installed"
        REQUIRED_OK=false
    fi
done

if [[ "$REQUIRED_OK" == "false" ]]; then
    echo ""
    echo "  Missing required packages. Install with:"
    echo "    pip install requests pytest"
    exit 1
fi

# -----------------------------------------------------------------------------
# Optional Packages (Native Client Tests)
# -----------------------------------------------------------------------------
echo ""
echo "Optional Packages (Native Client Tests):"
echo ""

# These packages are optional for native LanceDB client smoke tests
# Tests will be skipped gracefully if these packages are unavailable

OPTIONAL_PACKAGES="lancedb lance_namespace pyarrow"
OPTIONAL_STATUS=""

for pkg in $OPTIONAL_PACKAGES; do
    if python3 -c "import $pkg" 2>/dev/null; then
        VERSION=$(python3 -c "import $pkg; print($pkg.__version__ if hasattr($pkg, '__version__') else 'available')" 2>/dev/null || echo "available")
        echo "  ✓ $pkg: $VERSION"
    else
        echo "  - $pkg: NOT installed (optional)"
        OPTIONAL_STATUS="${OPTIONAL_STATUS}missing,"
    fi
done

if [[ -n "$OPTIONAL_STATUS" ]]; then
    echo ""
    echo "  Note: Optional packages are NOT required for Phase 1 validation."
    echo "        Raw HTTP tests serve as the primary validation mechanism."
    echo ""
    echo "  To enable native client tests, install:"
    echo "    pip install lancedb lance-namespace pyarrow"
fi

# -----------------------------------------------------------------------------
# UC Server Check
# -----------------------------------------------------------------------------
echo ""
echo "UC Server Check:"
echo ""

# Check UC server health endpoint
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$UC_HOST/api/2.1/unity-catalog/catalogs" 2>/dev/null || echo "000")

if [[ "$HTTP_STATUS" == "200" ]]; then
    echo "  ✓ UC server running"
    echo "    Host:   $UC_HOST"
    echo "    Status: HTTP $HTTP_STATUS"
elif [[ "$HTTP_STATUS" == "401" ]]; then
    echo "  ✓ UC server running (requires authentication)"
    echo "    Host:   $UC_HOST"
    echo "    Status: HTTP $HTTP_STATUS"
    echo "    Note:   Tests may need auth configuration"
else
    echo "  ✗ UC server NOT reachable"
    echo "    Host:   $UC_HOST"
    echo "    Status: HTTP $HTTP_STATUS"
    echo ""
    echo "    To start UC server:"
    echo "      cd /home/lei/data_ai/learning/codebase/unitycatalog"
    echo "      ./build/sbt server/run"
    echo ""
    echo "    Or check if server is running on a different port."
fi

# -----------------------------------------------------------------------------
# Lance REST Endpoint Check
# -----------------------------------------------------------------------------
echo ""
echo "UC Lance REST Endpoint Check:"
echo ""

# Check if Lance REST endpoint is mounted
LANCE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$UC_LANCE_URI/v1/namespace/list" 2>/dev/null || echo "000")

if [[ "$LANCE_STATUS" == "200" ]]; then
    echo "  ✓ Lance REST endpoint available"
    echo "    URI:    $UC_LANCE_URI"
    echo "    Status: HTTP $LANCE_STATUS"
elif [[ "$LANCE_STATUS" == "401" ]]; then
    echo "  ✓ Lance REST endpoint available (requires authentication)"
    echo "    URI:    $UC_LANCE_URI"
    echo "    Status: HTTP $LANCE_STATUS"
elif [[ "$LANCE_STATUS" == "404" ]]; then
    echo "  ✗ Lance REST endpoint NOT mounted"
    echo "    URI:    $UC_LANCE_URI"
    echo "    Status: HTTP $LANCE_STATUS"
    echo ""
    echo "    Lance route may not be enabled in UC server."
    echo "    Check UnityCatalogServer.java for Lance route configuration."
else
    echo "  ? Lance REST endpoint status unknown"
    echo "    URI:    $UC_LANCE_URI"
    echo "    Status: HTTP $LANCE_STATUS"
fi

# ==============================================================================
# Environment Summary
# ==============================================================================

echo ""
echo "=== Environment Summary ==="
echo ""

# Determine overall readiness
if [[ "$REQUIRED_OK" == "true" ]]; then
    echo "Required packages: READY"
    echo ""
    echo "Environment is ready for Phase 1 smoke tests."
    echo ""
    echo "Run tests:"
    echo "  ./run_tests.sh                    # Run all tests"
    echo "  ./run_tests.sh --raw-http-only    # Run only required tests"
    echo "  ./run_tests.sh --native-only      # Run only optional tests"
    echo ""

    if [[ "$HTTP_STATUS" != "200" && "$HTTP_STATUS" != "401" ]]; then
        echo "WARNING: UC server not detected at $UC_HOST"
        echo "         Tests will fail if server is not running."
        echo ""
    fi

    if [[ -n "$OPTIONAL_STATUS" ]]; then
        echo "Optional packages: SOME MISSING"
        echo "                   Native client tests will be skipped."
        echo ""
    fi

    exit 0
else
    echo "Required packages: MISSING"
    echo ""
    echo "Please install required packages before running tests:"
    echo "  pip install requests pytest"
    echo ""
    exit 1
fi