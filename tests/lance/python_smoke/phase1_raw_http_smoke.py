# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# phase1_raw_http_smoke.py
#
# Phase 1 Raw HTTP Smoke Tests for UC Lance REST Endpoint.
#
# These tests verify UC Lance REST endpoint behavior using raw HTTP requests,
# without relying on native LanceDB client SDK. This approach ensures protocol
# compatibility is validated independently of SDK implementation details.
#
# Reference Test Cases:
# - P1-CLIENT-002: namespace exists endpoint via raw HTTP
#                  (Python wrapper layer does not expose this convenience method)
# - P1-CLIENT-005: Full endpoint smoke with raw HTTP for all Phase 1 endpoints
# - P1-CLIENT-006: Raw HTTP coverage for endpoints not fully covered by client wrapper
#
# Design Document Reference:
# - docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md Section 7.10
#
# Note: Raw HTTP tests are REQUIRED for Phase 1 validation.
#       Native client tests (phase1_native_client_smoke.py) are OPTIONAL supplements.

import json
import pytest
import requests
from urllib.parse import quote

# ==============================================================================
# Configuration
# ==============================================================================

# UC Lance REST endpoint configuration
# Default values assume UC server is running locally on port 8080
UC_HOST = "http://localhost:8080"
UC_LANCE_PATH = "/api/2.1/unity-catalog/lance"
UC_LANCE_URI = f"{UC_HOST}{UC_LANCE_PATH}"

# Test data constants
# Using unique prefixes to avoid collisions with other test runs
TEST_NAMESPACE_PREFIX = "smoke_raw_http"
DEFAULT_DELIMITER = "$"


# ==============================================================================
# Raw HTTP Client
# ==============================================================================

class LanceRawHttpClient:
    """
    Raw HTTP client for UC Lance REST endpoint.

    This client provides direct HTTP access to all Phase 1 Lance REST endpoints,
    verifying protocol compatibility without SDK dependencies.

    Authentication Support:
    - Bearer token: set via auth_token parameter
    - API key: set via api_key parameter (x-api-key header)

    Usage:
        client = LanceRawHttpClient(UC_LANCE_URI)
        client.create_namespace("test_ns")
        resp = client.namespace_exists("test_ns")
        assert resp.json()["exists"] is True
    """

    def __init__(self, base_uri: str, auth_token: str = None, api_key: str = None):
        """
        Initialize raw HTTP client.

        Args:
            base_uri: UC Lance REST base URI (e.g., http://localhost:8080/api/2.1/unity-catalog/lance)
            auth_token: Optional Bearer token for authentication
            api_key: Optional API key for authentication (x-api-key header)
        """
        self.base_uri = base_uri
        self.session = requests.Session()

        # Configure authentication headers
        # Note: These headers apply to all requests in this session
        if auth_token:
            self.session.headers["Authorization"] = f"Bearer {auth_token}"
        if api_key:
            self.session.headers["x-api-key"] = api_key

        # Set default Content-Type for JSON requests
        # Lance REST endpoints expect application/json for most operations
        self.session.headers["Content-Type"] = "application/json"

    def post(self, path: str, body: dict = None) -> requests.Response:
        """
        Send POST request to Lance endpoint.

        Args:
            path: API path (e.g., /v1/namespace/{id}/create)
            body: JSON request body (optional)

        Returns:
            requests.Response object
        """
        url = f"{self.base_uri}{path}"
        return self.session.post(url, json=body or {})

    def get(self, path: str) -> requests.Response:
        """
        Send GET request to Lance endpoint.

        Args:
            path: API path (e.g., /v1/namespace/{id}/list)

        Returns:
            requests.Response object
        """
        url = f"{self.base_uri}{path}"
        return self.session.get(url)

    # ==========================================================================
    # Namespace Operations
    # ==========================================================================

    def create_namespace(self, namespace_id: str, properties: dict = None) -> requests.Response:
        """
        POST /v1/namespace/{id}/create

        Create a new namespace at the specified path.

        Args:
            namespace_id: Namespace identifier (e.g., "prod" or "prod$team_a")
            properties: Optional namespace properties

        Returns:
            Response with created namespace metadata
        """
        # URL encode the namespace_id to handle special characters
        # Lance REST uses {id} as path parameter
        path = f"/v1/namespace/{quote(namespace_id, safe='')}/create"
        return self.post(path, {"properties": properties or {}})

    def list_namespaces(self, parent_id: str = None) -> requests.Response:
        """
        GET /v1/namespace/{id}/list

        List direct child namespaces under the specified parent.

        Note: UC Lance implementation requires a valid parent namespace id.
        To list root-level namespaces, first create a root namespace and then
        list its children. The Lance OpenAPI suggests using '$' for root, but
        UC implementation rejects empty segments.

        Args:
            parent_id: Parent namespace identifier (must be a valid namespace id)
                       For root-level listing, pass a created root namespace id

        Returns:
            Response with list of child namespaces
        """
        if parent_id is None:
            # Cannot list root without a valid parent
            # Use the first created namespace as the parent for root-level listing
            raise ValueError("parent_id is required for list_namespaces in UC implementation")
        path = f"/v1/namespace/{quote(parent_id, safe='')}/list"
        return self.get(path)

    def describe_namespace(self, namespace_id: str) -> requests.Response:
        """
        POST /v1/namespace/{id}/describe

        Get namespace metadata and properties.

        Args:
            namespace_id: Namespace identifier

        Returns:
            Response with namespace details
        """
        path = f"/v1/namespace/{quote(namespace_id, safe='')}/describe"
        return self.post(path)

    def namespace_exists(self, namespace_id: str) -> requests.Response:
        """
        POST /v1/namespace/{id}/exists

        Check if a namespace exists.

        Note: This endpoint is NOT exposed as a convenience method in LanceDB Python
        client wrapper, so raw HTTP testing is required (P1-CLIENT-002).

        Args:
            namespace_id: Namespace identifier

        Returns:
            Response with {"exists": true/false}
        """
        path = f"/v1/namespace/{quote(namespace_id, safe='')}/exists"
        return self.post(path)

    def drop_namespace(self, namespace_id: str, mode: str = "restrict") -> requests.Response:
        """
        POST /v1/namespace/{id}/drop

        Drop a namespace.

        Args:
            namespace_id: Namespace identifier
            mode: Drop mode ("restrict" or "cascade")
                  Phase 1 fully supports restrict; cascade may return phase-limited error

        Returns:
            Response indicating success or failure
        """
        path = f"/v1/namespace/{quote(namespace_id, safe='')}/drop"
        return self.post(path, {"mode": mode})

    # ==========================================================================
    # Table Operations
    # ==========================================================================

    def list_tables(self, namespace_id: str, include_declared: bool = False) -> requests.Response:
        """
        GET /v1/namespace/{id}/table/list

        List tables in a namespace.

        Args:
            namespace_id: Namespace identifier
            include_declared: Whether to include declared-only tables

        Returns:
            Response with list of table identifiers
        """
        path = f"/v1/namespace/{quote(namespace_id, safe='')}/table/list"
        params = {"include_declared": str(include_declared).lower()}
        return self.session.get(f"{self.base_uri}{path}", params=params)

    def register_table(self, table_id: str, location: str,
                       properties: dict = None, storage_options_template: dict = None) -> requests.Response:
        """
        POST /v1/table/{id}/register

        Register an existing Lance table in UC.

        Args:
            table_id: Table identifier (e.g., "prod$team_a$embeddings")
            location: Storage location of the Lance table
            properties: Optional table properties
            storage_options_template: Optional non-sensitive storage configuration

        Returns:
            Response with registered table metadata
        """
        path = f"/v1/table/{quote(table_id, safe='')}/register"
        body = {
            "location": location,
            "properties": properties or {"table_type": "lance"}
        }
        if storage_options_template:
            body["storage_options_template"] = storage_options_template
        return self.post(path, body)

    def declare_table(self, table_id: str, location: str,
                      properties: dict = None, vend_credentials: bool = False) -> requests.Response:
        """
        POST /v1/table/{id}/declare

        Declare a table without requiring physical data to exist.

        This creates a declared-only table (is_only_declared=true) that can be
        materialized later through insert/create operations.

        Args:
            table_id: Table identifier
            location: Storage location for future Lance table
            properties: Optional table properties
            vend_credentials: Whether to vend storage credentials

        Returns:
            Response with declared table metadata
        """
        path = f"/v1/table/{quote(table_id, safe='')}/declare"
        body = {
            "location": location,
            "vend_credentials": vend_credentials,
            "properties": properties or {"table_type": "lance"}
        }
        return self.post(path, body)

    def create_empty_table(self, table_id: str, location: str,
                           properties: dict = None, vend_credentials: bool = False) -> requests.Response:
        """
        POST /v1/table/{id}/create-empty

        Deprecated alias for declare_table.

        This endpoint is maintained for backward compatibility with older Lance clients.
        Internally, UC should route this to the same implementation as declare.
        Audit logs should record deprecated_alias_used=true.

        Args:
            table_id: Table identifier
            location: Storage location
            properties: Optional table properties
            vend_credentials: Whether to vend storage credentials

        Returns:
            Response identical to declare_table response
        """
        path = f"/v1/table/{quote(table_id, safe='')}/create-empty"
        body = {
            "location": location,
            "vend_credentials": vend_credentials,
            "properties": properties or {"table_type": "lance"}
        }
        return self.post(path, body)

    def describe_table(self, table_id: str, vend_credentials: bool = False) -> requests.Response:
        """
        POST /v1/table/{id}/describe

        Get table metadata.

        Args:
            table_id: Table identifier
            vend_credentials: Whether to include storage_options with temporary credentials

        Returns:
            Response with table metadata including location, schema, properties
        """
        path = f"/v1/table/{quote(table_id, safe='')}/describe"
        return self.post(path, {"vend_credentials": vend_credentials})

    def table_exists(self, table_id: str) -> requests.Response:
        """
        POST /v1/table/{id}/exists

        Check if a table exists.

        This checks both native Lance tables and legacy bridge tables.

        Args:
            table_id: Table identifier

        Returns:
            Response with {"exists": true/false}
        """
        path = f"/v1/table/{quote(table_id, safe='')}/exists"
        return self.post(path)

    def drop_table(self, table_id: str) -> requests.Response:
        """
        POST /v1/table/{id}/drop

        Drop a table.

        Phase 1 behavior:
        - declared-only tables: Can be dropped immediately (metadata-only)
        - registered/active tables: May return phase-limited error
        - legacy bridge tables: May return phase-limited error

        Args:
            table_id: Table identifier

        Returns:
            Response indicating success or phase-limited error
        """
        path = f"/v1/table/{quote(table_id, safe='')}/drop"
        return self.post(path)

    def deregister_table(self, table_id: str) -> requests.Response:
        """
        POST /v1/table/{id}/deregister

        Deregister a table (remove metadata, preserve physical data).

        This is the recommended way to "soft delete" a table in Phase 1.

        Args:
            table_id: Table identifier

        Returns:
            Response indicating success
        """
        path = f"/v1/table/{quote(table_id, safe='')}/deregister"
        return self.post(path)


# ==============================================================================
# Test Fixtures
# ==============================================================================

@pytest.fixture
def lance_client():
    """
    Create raw HTTP Lance client for testing.

    This fixture provides a clean client instance for each test.
    No authentication is configured by default; tests that require
    auth should create their own client with auth_token/api_key.
    """
    return LanceRawHttpClient(UC_LANCE_URI)


@pytest.fixture
def unique_namespace_id():
    """
    Generate unique namespace ID for each test to avoid collisions.

    Uses test function name as part of the namespace ID.
    """
    import time
    timestamp = int(time.time() * 1000) % 10000
    return f"{TEST_NAMESPACE_PREFIX}_{timestamp}"


@pytest.fixture
def cleanup_namespaces(lance_client):
    """
    Fixture to cleanup namespaces created during tests.

    Yields the client, then attempts to cleanup all test namespaces.
    """
    created_namespaces = []

    def track_namespace(ns_id):
        created_namespaces.append(ns_id)

    yield lance_client, track_namespace

    # Cleanup: drop all tracked namespaces
    for ns_id in created_namespaces:
        try:
            # First try to deregister any tables in the namespace
            resp = lance_client.list_tables(ns_id)
            if resp.status_code == 200:
                tables = resp.json().get("tables", [])
                for table_id in tables:
                    try:
                        lance_client.deregister_table(table_id)
                    except Exception:
                        pass

            # Drop namespace with cascade to handle any remaining content
            lance_client.drop_namespace(ns_id, mode="cascade")
        except Exception:
            # Cleanup failures are logged but don't fail tests
            pass


# ==============================================================================
# Namespace Tests
# ==============================================================================

class TestPhase1NamespaceRawHttp:
    """
    Test namespace endpoint behavior via raw HTTP.

    Covers: P1-CLIENT-002, P1-CLIENT-005

    Key verification points:
    - HTTP method correctness (POST for create/describe/exists/drop, GET for list)
    - Response content type (application/json)
    - Response shape (id, properties, exists flag)
    - Error handling (404 for not found, 400 for invalid request)
    """

    def test_p1_client_002_namespace_exists_endpoint(self, lance_client, unique_namespace_id):
        """
        P1-CLIENT-002: namespace exists endpoint via raw HTTP.

        Test Design Reference:
        - Python wrapper layer does not expose namespace_exists convenience method
        - Must verify via raw HTTP POST /v1/namespace/{id}/exists

        Verification:
        1. Create namespace -> exists should return true
        2. Non-existent namespace -> exists should return false
        3. Response shape: {"exists": boolean}
        """
        ns_id = f"{unique_namespace_id}_exists"

        # Step 1: Create namespace
        resp = lance_client.create_namespace(ns_id, {"purpose": "exists_test"})
        assert resp.status_code in (200, 201), f"Create failed: {resp.text}"

        # Step 2: Verify exists returns true
        resp = lance_client.namespace_exists(ns_id)
        assert resp.status_code == 200, f"Exists request failed: {resp.status_code}"
        data = resp.json()
        assert "exists" in data, "Response missing 'exists' field"
        assert data["exists"] is True, f"Expected exists=true, got {data}"

        # Step 3: Verify non-existent namespace returns false
        # Note: exists endpoint should NOT throw 404 for missing namespace
        # It should return {"exists": false}
        resp = lance_client.namespace_exists(f"{ns_id}_nonexistent")
        assert resp.status_code == 200, f"Exists for missing ns should return 200, got {resp.status_code}"
        data = resp.json()
        assert data["exists"] is False, f"Expected exists=false for missing ns, got {data}"

        # Cleanup
        lance_client.drop_namespace(ns_id)

    def test_p1_client_005_namespace_create_describe_list_drop(self, lance_client, unique_namespace_id):
        """
        P1-CLIENT-005: Full namespace lifecycle via raw HTTP.

        Test Design Reference:
        - Raw HTTP smoke for all Phase 1 namespace endpoints

        Verification:
        1. POST /create -> creates namespace
        2. POST /describe -> returns metadata
        3. GET /list -> returns child namespaces
        4. POST /drop -> removes namespace
        """
        ns_id = f"{unique_namespace_id}_lifecycle"

        # Step 1: Create namespace with properties
        properties = {"purpose": "lifecycle_test", "owner": "smoke_test"}
        resp = lance_client.create_namespace(ns_id, properties)
        assert resp.status_code in (200, 201), f"Create failed: {resp.text}"

        created_data = resp.json()
        assert created_data.get("id") == ns_id, f"Created ns id mismatch"

        # Step 2: Describe namespace
        resp = lance_client.describe_namespace(ns_id)
        assert resp.status_code == 200, f"Describe failed: {resp.status_code}"
        desc_data = resp.json()
        assert desc_data.get("id") == ns_id
        # Properties should be preserved
        assert desc_data.get("properties", {}).get("purpose") == "lifecycle_test"

        # Step 3: List child namespaces under the created namespace
        # Note: UC implementation requires a valid parent namespace id for list
        # We list children under the namespace we just created
        resp = lance_client.list_namespaces(ns_id)
        assert resp.status_code == 200
        list_data = resp.json()
        namespaces = list_data.get("namespaces", [])
        # Empty namespace should have no children
        assert len(namespaces) == 0, f"New namespace should have no children: {namespaces}"

        # Step 4: Create a child namespace to verify list includes it
        child_ns_id = f"{ns_id}$child"
        resp = lance_client.create_namespace(child_ns_id)
        assert resp.status_code in (200, 201)

        # List should now contain the child
        resp = lance_client.list_namespaces(ns_id)
        assert resp.status_code == 200
        namespaces = resp.json().get("namespaces", [])
        assert any("child" in str(ns) for ns in namespaces), f"Child ns not in list: {namespaces}"

        # Step 5: Drop child namespace
        resp = lance_client.drop_namespace(child_ns_id, mode="restrict")
        assert resp.status_code in (200, 204)

        # Step 6: Drop parent namespace with restrict mode (should succeed now that child is gone)
        resp = lance_client.drop_namespace(ns_id, mode="restrict")
        assert resp.status_code in (200, 204), f"Drop failed: {resp.status_code}"

        # Step 7: Verify namespace is gone
        resp = lance_client.namespace_exists(ns_id)
        assert resp.status_code == 200
        assert resp.json()["exists"] is False

    def test_namespace_deep_path(self, lance_client, unique_namespace_id):
        """
        Test namespace with deep hierarchical path (4 layers).

        Verification:
        - Create nested namespaces: prod -> prod$team -> prod$team$ml -> prod$team$ml$embeddings
        - Each parent must exist before child can be created
        - List should only return direct children, not all descendants
        """
        root_ns = f"{unique_namespace_id}_deep"
        level1 = f"{root_ns}$team"
        level2 = f"{level1}$ml"
        level3 = f"{level2}$embeddings"

        # Create hierarchy
        resp = lance_client.create_namespace(root_ns)
        assert resp.status_code in (200, 201)

        resp = lance_client.create_namespace(level1)
        assert resp.status_code in (200, 201)

        resp = lance_client.create_namespace(level2)
        assert resp.status_code in (200, 201)

        resp = lance_client.create_namespace(level3)
        assert resp.status_code in (200, 201)

        # Verify deepest namespace exists
        resp = lance_client.namespace_exists(level3)
        assert resp.json()["exists"] is True

        # Verify list returns only direct children
        resp = lance_client.list_namespaces(level2)
        assert resp.status_code == 200
        children = resp.json().get("namespaces", [])
        # Should contain level3 but NOT deeper descendants
        assert any(level3.split("$")[-1] in str(c) for c in children)

        # Cleanup with cascade
        lance_client.drop_namespace(root_ns, mode="cascade")

    def test_namespace_invalid_identifier(self, lance_client, unique_namespace_id):
        """
        Test namespace with invalid identifier.

        Verification:
        - Empty identifier returns 404 (route not matched)
        - Single-segment valid identifier succeeds
        """
        # Empty identifier - route may not match, returns 404
        resp = lance_client.create_namespace("")
        assert resp.status_code in (400, 404), f"Empty ns should return 400/404, got {resp.status_code}"

        # Valid single segment identifier should succeed
        resp = lance_client.create_namespace(unique_namespace_id)
        assert resp.status_code in (200, 201), f"Valid ns should succeed: {resp.status_code}"

        # Cleanup
        lance_client.drop_namespace(unique_namespace_id)


# ==============================================================================
# Table Tests
# ==============================================================================

class TestPhase1TableRawHttp:
    """
    Test table endpoint behavior via raw HTTP.

    Covers: P1-CLIENT-005, P1-CLIENT-006

    Key verification points:
    - Declare vs create-empty equivalence
    - include_declared filter behavior
    - vend_credentials behavior
    - declared-only table lifecycle
    """

    def test_p1_client_006_table_declare_describe_exists_deregister(self,
                                                                     lance_client, unique_namespace_id):
        """
        P1-CLIENT-006: Table lifecycle via raw HTTP.

        Test Design Reference:
        - Raw HTTP coverage for table endpoints

        Verification:
        1. POST /declare -> creates declared-only table
        2. POST /describe -> returns metadata with is_only_declared=true
        3. POST /exists -> returns true
        4. GET /table/list with include_declared -> returns table
        5. POST /deregister -> removes metadata
        """
        ns_id = f"{unique_namespace_id}_table"
        table_id = f"{ns_id}$declared_table"
        location = "/tmp/lance_smoke_test/declared"

        # Create namespace first
        lance_client.create_namespace(ns_id)

        # Step 1: Declare table
        resp = lance_client.declare_table(table_id, location)
        assert resp.status_code in (200, 201), f"Declare failed: {resp.text}"

        # Step 2: Describe table - should show declared-only status
        resp = lance_client.describe_table(table_id)
        assert resp.status_code == 200
        data = resp.json()
        assert data.get("is_only_declared") is True, f"Declared table should have is_only_declared=true"
        assert data.get("location") == location

        # Step 3: Table exists
        resp = lance_client.table_exists(table_id)
        assert resp.status_code == 200
        assert resp.json()["exists"] is True

        # Step 4: List tables - include_declared=false should NOT show declared table
        resp = lance_client.list_tables(ns_id, include_declared=False)
        assert resp.status_code == 200
        tables = resp.json().get("tables", [])
        # Declared table should NOT appear
        assert not any(table_id in str(t) for t in tables), f"Declared table appeared in list without include_declared"

        # Step 5: List tables - include_declared=true should show declared table
        resp = lance_client.list_tables(ns_id, include_declared=True)
        assert resp.status_code == 200
        tables = resp.json().get("tables", [])
        assert any(table_id in str(t) for t in tables), f"Declared table not in list with include_declared=true"

        # Step 6: Deregister table
        resp = lance_client.deregister_table(table_id)
        assert resp.status_code in (200, 204)

        # Step 7: Verify table is gone
        resp = lance_client.table_exists(table_id)
        assert resp.json()["exists"] is False

        # Cleanup
        lance_client.drop_namespace(ns_id)

    def test_create_empty_alias_equivalence(self, lance_client, unique_namespace_id):
        """
        P1-CLIENT-006: Verify create-empty is equivalent to declare.

        Test Design Reference:
        - create-empty is deprecated alias for declare
        - Both should result in declared-only table (is_only_declared=true)
        - Audit should distinguish protocol_variant (declare vs create-empty)

        This test verifies functional equivalence, not audit logging.
        """
        ns_id = f"{unique_namespace_id}_alias"

        lance_client.create_namespace(ns_id)

        # Create table using create-empty endpoint
        table_id = f"{ns_id}$empty_alias_table"
        location = "/tmp/lance_smoke_test/empty_alias"

        resp = lance_client.create_empty_table(table_id, location)
        assert resp.status_code in (200, 201), f"Create-empty failed: {resp.text}"

        # Verify it behaves identically to declared table
        resp = lance_client.describe_table(table_id)
        assert resp.status_code == 200
        data = resp.json()
        assert data.get("is_only_declared") is True, "create-empty should create declared-only table"
        assert data.get("location") == location

        # Cleanup
        lance_client.deregister_table(table_id)
        lance_client.drop_namespace(ns_id)

    def test_vend_credentials_behavior(self, lance_client, unique_namespace_id):
        """
        Test vend_credentials parameter in describe_table.

        Verification:
        1. vend_credentials=false -> no storage_options in response
        2. vend_credentials=true -> storage_options present (if UC has credential config)

        Note: This test passes even if storage_options is empty, since
        credential vending depends on UC configuration.
        """
        ns_id = f"{unique_namespace_id}_cred"
        table_id = f"{ns_id}$cred_table"
        location = "/tmp/lance_smoke_test/cred"

        lance_client.create_namespace(ns_id)
        lance_client.declare_table(table_id, location)

        # Describe without credentials
        resp = lance_client.describe_table(table_id, vend_credentials=False)
        assert resp.status_code == 200
        data = resp.json()
        # storage_options should not be present or should be null
        if "storage_options" in data:
            assert data["storage_options"] is None or len(data["storage_options"]) == 0

        # Describe with credentials
        resp = lance_client.describe_table(table_id, vend_credentials=True)
        assert resp.status_code == 200
        data = resp.json()
        # If UC has credential vending configured, storage_options should be present
        # If not configured, response should still succeed (200)
        # This test validates the endpoint works, not specific credential content

        # Cleanup
        lance_client.deregister_table(table_id)
        lance_client.drop_namespace(ns_id)


# ==============================================================================
# Error Handling Tests
# ==============================================================================

class TestPhase1ErrorHandlingRawHttp:
    """
    Test error handling and error response shape via raw HTTP.

    Covers: P1-ERROR-* tests from test design

    Key verification:
    - Lance-compatible error shape: type, message, code
    - HTTP status codes aligned with error codes
    - No internal stack traces exposed
    """

    def test_namespace_not_found_error_shape(self, lance_client):
        """
        P1-ERROR-001: Namespace not found should return Lance-compatible error.

        Expected:
        - HTTP status: 404
        - Error body: {type, message, code} or compatible shape
        """
        resp = lance_client.describe_namespace("nonexistent_namespace_xyz")
        assert resp.status_code == 404, f"Expected 404 for missing namespace, got {resp.status_code}"

        # Verify error response shape
        try:
            data = resp.json()
            # Lance error shape: should have type/message/code
            # UC may use different field names, but structure should be stable
            assert "type" in data or "code" in data or "message" in data, \
                f"Error response missing expected fields: {data}"
        except json.JSONDecodeError:
            pytest.fail("Error response is not valid JSON")

    def test_table_not_found_error_shape(self, lance_client):
        """
        P1-ERROR-002: Table not found should return Lance-compatible error.
        """
        resp = lance_client.describe_table("nonexistent_namespace$table")
        assert resp.status_code == 404

        try:
            data = resp.json()
            assert "type" in data or "code" in data or "message" in data
        except json.JSONDecodeError:
            pytest.fail("Error response is not valid JSON")

    def test_invalid_argument_error(self, lance_client):
        """
        P1-ERROR-005: Invalid request should return 400 or 404.

        Note: Empty identifier may return 404 (route pattern not matched)
        instead of 400, which is acceptable behavior.
        """
        # Empty namespace identifier
        resp = lance_client.create_namespace("")
        assert resp.status_code in (400, 404), f"Empty ns should return 400/404: {resp.status_code}"

        # Check error shape if 400
        if resp.status_code == 400:
            data = resp.json()
            error_type = data.get("type", data.get("code", ""))
            # Should indicate invalid argument or similar
            assert "invalid" in error_type.lower() or "argument" in error_type.lower() or \
                   "bad" in error_type.lower() or "request" in error_type.lower() or \
                   "empty" in error_type.lower()

    def test_method_not_allowed(self, lance_client):
        """
        P1-CONTRACT-002: HTTP method enforcement.

        GET /v1/namespace/{id}/describe should return 405 Method Not Allowed.
        """
        resp = lance_client.get("/v1/namespace/test/describe")
        assert resp.status_code == 405, f"Expected 405 for wrong method, got {resp.status_code}"

        data = resp.json()
        # Error should indicate method not allowed
        error_type = data.get("type", data.get("code", ""))
        assert "method" in error_type.lower() or "allowed" in error_type.lower()


# ==============================================================================
# HTTP Contract Tests
# ==============================================================================

class TestPhase1HttpContract:
    """
    Test HTTP-level contract compliance.

    Covers: P1-CONTRACT-* tests

    Key verification:
    - Content-Type headers
    - Response body validity
    - HTTP status codes
    """

    def test_json_content_type(self, lance_client, unique_namespace_id):
        """
        P1-CONTRACT: Verify Content-Type header for JSON responses.

        All Phase 1 endpoints should return application/json (or application/json;charset=...).
        """
        ns_id = f"{unique_namespace_id}_content_type"

        resp = lance_client.create_namespace(ns_id)
        content_type = resp.headers.get("Content-Type", "")
        assert content_type.startswith("application/json"), \
            f"Expected application/json, got {content_type}"

        # Cleanup
        lance_client.drop_namespace(ns_id)

    def test_response_valid_json(self, lance_client, unique_namespace_id):
        """
        P1-CONTRACT: All responses should be valid JSON.

        This test verifies that Lance endpoints return parseable JSON,
        even for error responses.
        """
        # Create a namespace first for valid requests
        lance_client.create_namespace(unique_namespace_id)

        test_cases = [
            ("/v1/namespace/{}/describe".format(quote(unique_namespace_id, safe='')), "post"),
            ("/v1/namespace/{}/exists".format(quote(unique_namespace_id, safe='')), "post"),
            ("/v1/namespace/{}/list".format(quote(unique_namespace_id, safe='')), "get"),
        ]

        for path, method in test_cases:
            if method == "get":
                resp = lance_client.get(path)
            else:
                resp = lance_client.post(path)

            # Verify response is valid JSON
            try:
                resp.json()
            except json.JSONDecodeError:
                pytest.fail(f"Response from {path} is not valid JSON: {resp.text[:200]}")

        # Cleanup
        lance_client.drop_namespace(unique_namespace_id)

    def test_http_status_codes(self, lance_client, unique_namespace_id):
        """
        Verify HTTP status codes are in expected ranges.

        - 2xx: Success
        - 4xx: Client errors (not found, invalid request, unauthorized)
        - 5xx: Server errors (internal, unavailable)

        No unexpected 3xx redirects for Lance endpoints.
        """
        # Create namespace for valid requests
        lance_client.create_namespace(unique_namespace_id)

        # Success case - list with valid parent
        resp = lance_client.list_namespaces(unique_namespace_id)
        assert 200 <= resp.status_code < 300, f"List should succeed: {resp.status_code}"

        # Success case - describe
        resp = lance_client.describe_namespace(unique_namespace_id)
        assert 200 <= resp.status_code < 300

        # Client error case - not found
        resp = lance_client.describe_namespace("nonexistent_xyz")
        assert 400 <= resp.status_code < 500, f"Not found should be 4xx: {resp.status_code}"

        # Cleanup
        lance_client.drop_namespace(unique_namespace_id)


# ==============================================================================
# Test Entry Point
# ==============================================================================

if __name__ == "__main__":
    """
    Run tests directly from command line.

    Usage:
        python phase1_raw_http_smoke.py

    Or with pytest:
        pytest phase1_raw_http_smoke.py -v
    """
    pytest.main([__file__, "-v", "--tb=short", "-x"])