# Phase 1 Lance Client Smoke Tests

This directory contains smoke tests for Phase 1 Lance REST API endpoints in Unity Catalog.

## Overview

These tests verify UC Lance REST endpoint behavior using two approaches:

1. **Raw HTTP Tests** (Required) - Primary validation using direct HTTP requests
2. **Native Client Tests** (Optional) - LanceDB SDK compatibility testing

## Directory Structure

```
python_smoke/
├── requirements.txt              # Test dependencies
├── phase1_raw_http_smoke.py      # Raw HTTP tests (required)
├── phase1_native_client_smoke.py # Native client tests (optional)
├── run_tests.sh                  # Test runner script
├── check_env.sh                  # Environment checker
└── README.md                     # This file
```

## Quick Start

### 1. Check Environment

```bash
./check_env.sh
```

This verifies:
- Python availability
- Required/optional packages
- UC server running
- Lance REST endpoint mounted

### 2. Run Tests

```bash
# Run all tests
./run_tests.sh

# Run only required tests (raw HTTP)
./run_tests.sh --raw-http-only

# Run only optional tests (native client)
./run_tests.sh --native-only
```

## Test Coverage

### Raw HTTP Tests (Required)

These tests cover all Phase 1 endpoints per test design document Section 7.10:

| Test Case | Endpoint | Description |
|-----------|----------|-------------|
| P1-CLIENT-002 | `/v1/namespace/{id}/exists` | Namespace existence check (not exposed by Python wrapper) |
| P1-CLIENT-005 | All namespace/table endpoints | Full endpoint smoke |
| P1-CLIENT-006 | `/v1/table/{id}/create-empty` | Deprecated alias verification |

Key verifications:
- HTTP method correctness
- Response content type
- Lance-compatible error shape
- Declared-only table behavior
- Storage credential vending

### Native Client Tests (Optional)

These tests verify LanceDB Python SDK compatibility:

| Test Case | Coverage |
|-----------|----------|
| P1-CLIENT-001 | Namespace: create/list/describe/drop |
| P1-CLIENT-001 | Table: create/open/drop |

Note: Native client tests are skipped gracefully if SDK unavailable.

## Dependencies

### Required (Raw HTTP Tests)

```bash
pip install requests pytest
```

### Optional (Native Client Tests)

```bash
pip install lancedb lance-namespace pyarrow
```

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `UC_HOST` | `http://localhost:8080` | UC server host |
| `UC_LANCE_URI` | `${UC_HOST}/api/2.1/unity-catalog/lance` | Lance REST base URI |

Example:
```bash
export UC_HOST="http://localhost:8081"
./run_tests.sh
```

## Test Design Reference

- `docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md` Section 7.10

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | All tests passed |
| 1 | Required tests failed |
| 2 | Environment setup failed |
| 3 | UC server not reachable |

## Notes

- Raw HTTP tests are the **primary validation mechanism** for Phase 1
- Native client tests validate SDK compatibility but are not gatekeepers
- Tests are skipped gracefully when optional dependencies are unavailable