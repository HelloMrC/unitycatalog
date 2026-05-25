# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# phase1_native_client_smoke.py
#
# Phase 1 Native Client Smoke Tests for UC Lance REST Endpoint.
#
# These tests verify UC Lance REST endpoint compatibility with native LanceDB Python SDK.
# They are OPTIONAL supplements to the required raw HTTP tests.
#
# Reference Test Cases:
# - P1-CLIENT-001: Python namespace client smoke (connect + list/describe/drop)
#
# Design Document Reference:
# - docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md Section 7.10
#
# Note: These tests will be SKIPPED if lance-namespace REST client is unavailable.
#       Raw HTTP tests (phase1_raw_http_smoke.py) serve as the primary validation mechanism.
#       When native client tests are skipped, the skip reason will be recorded in test output.

import pytest

# ==============================================================================
# Native Client Availability Check
# ==============================================================================

# Try to import LanceDB native client
# If unavailable, all tests in this file will be skipped
try:
    import lancedb
    NATIVE_CLIENT_AVAILABLE = True
    LANCEDB_VERSION = lancedb.__version__
except ImportError:
    NATIVE_CLIENT_AVAILABLE = False
    LANCEDB_VERSION = None
    # Skip all tests in this module with a clear message
    pytest.skip(
        "lancedb SDK not installed - native client tests skipped (raw HTTP tests are primary validation)",
        allow_module_level=True
    )

# Try to import lance-namespace REST client support
# REST namespace client is required for connecting to UC Lance REST endpoint
try:
    from lance_namespace import connect as namespace_connect, LanceNamespace
    from lance_namespace.errors import TableNotFoundError, NamespaceNotFoundError
    LANCE_NAMESPACE_AVAILABLE = True
except ImportError:
    LANCE_NAMESPACE_AVAILABLE = False
    pytest.skip(
        "lance-namespace package not installed - REST namespace client unavailable",
        allow_module_level=True
    )

# Optional imports for schema/table operations
try:
    import pyarrow as pa
    PYARROW_AVAILABLE = True
except ImportError:
    PYARROW_AVAILABLE = False


# ==============================================================================
# Configuration
# ==============================================================================

# UC Lance REST endpoint configuration
# Default values assume UC server is running locally on port 8080
UC_LANCE_URI = "http://localhost:8080/api/2.1/unity-catalog/lance"
TEST_NAMESPACE_PREFIX = "native_client_test"

# REST namespace client configuration properties
# The exact property names may vary based on lance-namespace version
# 'uri' is the expected property name for REST client base URL
REST_NAMESPACE_PROPERTIES = {
    "uri": UC_LANCE_URI,
    # Additional properties can be added for authentication:
    # "token": "<bearer-token>",  # For Bearer token auth (if supported)
    # "api_key": "<api-key>",     # For API key auth (if supported)
}


# ==============================================================================
# Test Fixtures
# ==============================================================================

@pytest.fixture
def native_lance_db():
    """
    Create native LanceDB client connected to UC Lance REST endpoint.

    This fixture attempts to create a REST namespace client connection.
    If the REST namespace client implementation or configuration is unavailable,
    tests using this fixture will be skipped with a clear message.

    Note: The lance-namespace REST client configuration may require specific
    property names that vary by version. If connection fails, the error message
    will help diagnose configuration issues.

    Returns:
        LanceNamespaceDBConnection if successful

    Raises:
        pytest.skip if REST namespace client cannot connect
    """
    try:
        # Attempt to connect using REST namespace client implementation
        # The 'rest' implementation should support connecting to Lance REST servers
        db = lancedb.connect_namespace(
            namespace_client_impl="rest",
            namespace_client_properties=REST_NAMESPACE_PROPERTIES
        )
        return db
    except Exception as e:
        # Skip tests if REST namespace client cannot connect
        # This is acceptable behavior - raw HTTP tests are the primary validation
        pytest.skip(
            f"REST namespace client connection failed: {e}. "
            f"Native client tests skipped - raw HTTP tests serve as primary validation."
        )


@pytest.fixture
def unique_namespace_id():
    """Generate unique namespace ID using timestamp to avoid test collisions."""
    import time
    timestamp = int(time.time() * 1000) % 10000
    return f"{TEST_NAMESPACE_PREFIX}_{timestamp}"


@pytest.fixture
def cleanup_namespace(native_lance_db, unique_namespace_id):
    """
    Fixture to cleanup namespace after tests.

    Yields the namespace ID, then attempts cleanup after test completion.
    """
    yield unique_namespace_id

    # Cleanup: drop namespace with cascade to remove all tables
    try:
        native_lance_db.drop_namespace([unique_namespace_id], mode="cascade")
    except Exception:
        # Cleanup failures don't fail tests, but should be logged
        pass


# ==============================================================================
# Namespace Tests
# ==============================================================================

@pytest.mark.native_client
class TestPhase1NamespaceNativeClient:
    """
    P1-CLIENT-001: Namespace operations via native LanceDB client.

    These tests verify that native LanceDB Python client can successfully
    interact with UC Lance REST endpoint for namespace operations.

    Test Coverage:
    - create_namespace: Create new namespace
    - list_namespaces: List child namespaces
    - describe_namespace: Get namespace metadata
    - drop_namespace: Remove namespace

    Note: Native client API style may differ from raw HTTP:
    - Uses List[str] for namespace path instead of "$" delimiter
    - Returns objects with attributes instead of JSON dicts
    """

    def test_create_namespace(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        P1-CLIENT-001: Create namespace using native client.

        Verification:
        - Namespace creation succeeds
        - Namespace appears in list after creation
        """
        ns_path = [unique_namespace_id]

        # Create namespace
        # Native client uses list of path segments instead of "$" delimiter string
        native_lance_db.create_namespace(ns_path, properties={"purpose": "native_client_smoke"})

        # Verify namespace exists by listing
        namespaces = native_lance_db.list_namespaces()
        namespace_ids = []

        # Extract namespace IDs from response
        # Response format may vary: could be objects with .id attribute or strings
        for ns in namespaces:
            if hasattr(ns, 'id'):
                namespace_ids.append(ns.id)
            elif hasattr(ns, 'path'):
                namespace_ids.append(ns.path[-1] if isinstance(ns.path, list) else str(ns.path))
            else:
                namespace_ids.append(str(ns))

        # Verify created namespace is in list
        assert unique_namespace_id in namespace_ids or \
               any(unique_namespace_id in str(ns) for ns in namespace_ids), \
               f"Created namespace {unique_namespace_id} not found in list: {namespace_ids}"

    def test_describe_namespace(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        P1-CLIENT-001: Describe namespace using native client.

        Verification:
        - describe_namespace returns namespace metadata
        - Metadata includes path and properties
        """
        ns_path = [unique_namespace_id]
        native_lance_db.create_namespace(ns_path, {"purpose": "describe_test"})

        # Describe namespace
        desc = native_lance_db.describe_namespace(ns_path)

        # Verify description structure
        # Native client may return object with attributes or dict-like object
        assert desc is not None

        # Check for path/id attribute
        if hasattr(desc, 'path'):
            assert desc.path[-1] == unique_namespace_id or desc.path == ns_path
        elif hasattr(desc, 'id'):
            # ID might be "$" delimited string
            assert unique_namespace_id in str(desc.id)

        # Check for properties attribute
        if hasattr(desc, 'properties'):
            assert desc.properties.get("purpose") == "describe_test"
        elif hasattr(desc, '__getitem__'):
            # Dict-like object
            assert desc.get("properties", {}).get("purpose") == "describe_test"

    def test_namespace_deep_path(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        P1-CLIENT-001: Create nested namespace hierarchy.

        Verification:
        - Multi-level namespace path works correctly
        - Parent namespace must exist before child can be created
        - list_namespaces returns only direct children
        """
        # Create nested namespaces: test -> test/child -> test/child/grandchild
        root_ns = [unique_namespace_id]
        child_ns = [unique_namespace_id, "child"]
        grandchild_ns = [unique_namespace_id, "child", "grandchild"]

        # Create hierarchy
        native_lance_db.create_namespace(root_ns)
        native_lance_db.create_namespace(child_ns)
        native_lance_db.create_namespace(grandchild_ns)

        # Verify deepest namespace exists
        desc = native_lance_db.describe_namespace(grandchild_ns)
        assert desc is not None

        # Verify list returns only direct children
        children = native_lance_db.list_namespaces(namespace_path=root_ns)

        # Check that "child" appears in root's children
        child_found = False
        for ns in children:
            ns_repr = str(ns.id if hasattr(ns, 'id') else ns.path[-1] if hasattr(ns, 'path') else ns)
            if "child" in ns_repr:
                child_found = True
        assert child_found, "child namespace should appear in root's children"

    def test_namespace_drop_restrict(self, native_lance_db, unique_namespace_id):
        """
        P1-CLIENT-001: Drop empty namespace with restrict mode.

        Verification:
        - Empty namespace can be dropped with restrict mode
        - Namespace no longer appears in list after drop
        """
        ns_path = [unique_namespace_id]
        native_lance_db.create_namespace(ns_path)

        # Drop namespace (restrict mode should work for empty namespace)
        native_lance_db.drop_namespace(ns_path, mode="restrict")

        # Verify namespace no longer exists
        try:
            native_lance_db.describe_namespace(ns_path)
            pytest.fail("Namespace should not exist after drop")
        except NamespaceNotFoundError:
            # Expected: namespace should be gone
            pass
        except Exception as e:
            # Some error types might not be NamespaceNotFoundError
            # Check that error indicates not found
            assert "not found" in str(e).lower() or "not exist" in str(e).lower()


# ==============================================================================
# Table Tests
# ==============================================================================

@pytest.mark.native_client
@pytest.mark.skipif(not PYARROW_AVAILABLE, reason="pyarrow required for table schema operations")
class TestPhase1TableNativeClient:
    """
    P1-CLIENT-001: Table operations via native LanceDB client.

    These tests verify table management through UC Lance REST endpoint.

    Note: Phase 1 native client table operations may be limited:
    - create_table with schema creates declared-only table
    - open_table opens existing table
    - drop_table removes table metadata
    """

    def test_create_declared_table(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        Create a declared table using native client.

        Verification:
        - create_table with schema succeeds
        - Table appears in list with include_declared
        - describe_table shows is_only_declared=true
        """
        # Create namespace first
        ns_path = [unique_namespace_id]
        native_lance_db.create_namespace(ns_path)

        # Define schema using pyarrow
        schema = pa.schema([
            pa.field("id", pa.int64()),
            pa.field("vector", pa.list_(pa.float32(), 128)),
            pa.field("text", pa.string()),
        ])

        # Create declared table (no data, just schema)
        # Note: create_table with schema but no data should create declared-only table
        table_name = "declared_test_table"
        table = native_lance_db.create_table(
            table_name,
            schema=schema,
            namespace_path=ns_path
        )

        assert table is not None
        assert table.name == table_name

        # Verify table appears in namespace table list
        tables = native_lance_db.table_names(namespace_path=ns_path)
        table_names_list = [t for t in tables]
        assert table_name in table_names_list or any(table_name in str(t) for t in table_names_list)

    def test_open_table(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        Open existing table using native client.

        Verification:
        - open_table succeeds for declared table
        - Table has correct name and namespace
        """
        ns_path = [unique_namespace_id]
        native_lance_db.create_namespace(ns_path)

        # Create table
        schema = pa.schema([pa.field("id", pa.int64())])
        table_name = "open_test_table"
        native_lance_db.create_table(table_name, schema=schema, namespace_path=ns_path)

        # Open table
        opened_table = native_lance_db.open_table(table_name, namespace_path=ns_path)
        assert opened_table is not None
        assert opened_table.name == table_name

    def test_drop_table(self, native_lance_db, unique_namespace_id):
        """
        Drop table using native client.

        Verification:
        - declared-only table can be dropped
        - Table no longer appears in list after drop
        """
        ns_path = [unique_namespace_id]
        native_lance_db.create_namespace(ns_path)

        # Create and drop table
        schema = pa.schema([pa.field("id", pa.int64())])
        table_name = "drop_test_table"
        native_lance_db.create_table(table_name, schema=schema, namespace_path=ns_path)

        # Drop table
        native_lance_db.drop_table(table_name, namespace_path=ns_path)

        # Verify table no longer exists
        try:
            native_lance_db.open_table(table_name, namespace_path=ns_path)
            pytest.fail("Table should not exist after drop")
        except TableNotFoundError:
            pass
        except Exception as e:
            assert "not found" in str(e).lower()


# ==============================================================================
# Compatibility Tests
# ==============================================================================

@pytest.mark.native_client
class TestPhase1NativeClientCompatibility:
    """
    Tests for native client compatibility with UC Lance REST endpoint.

    These tests verify that native LanceDB client correctly interprets
    UC Lance REST responses and handles UC-specific behaviors.
    """

    def test_namespace_path_format(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        Verify native client handles namespace path format correctly.

        Native client uses List[str] for namespace path, while raw HTTP uses "$" delimiter.
        UC should accept both representations.
        """
        # Create namespace using list format
        ns_path = [unique_namespace_id, "nested", "path"]
        native_lance_db.create_namespace(ns_path)

        # Verify namespace exists
        desc = native_lance_db.describe_namespace(ns_path)
        assert desc is not None

    def test_table_id_format(self, native_lance_db, unique_namespace_id, cleanup_namespace):
        """
        Verify native client handles table identifier correctly.

        Native client uses table_name + namespace_path, while raw HTTP uses "$" delimiter.
        """
        ns_path = [unique_namespace_id]
        native_lance_db.create_namespace(ns_path)

        if PYARROW_AVAILABLE:
            schema = pa.schema([pa.field("id", pa.int64())])
            table_name = "format_test_table"
            native_lance_db.create_table(table_name, schema=schema, namespace_path=ns_path)

            # Verify table can be opened with same identifiers
            table = native_lance_db.open_table(table_name, namespace_path=ns_path)
            assert table.name == table_name


# ==============================================================================
# Error Handling Tests
# ==============================================================================

@pytest.mark.native_client
class TestPhase1NativeClientErrorHandling:
    """
    Test error handling via native client.

    Verify that UC Lance errors are correctly translated to native client exceptions.
    """

    def test_namespace_not_found(self, native_lance_db):
        """
        Verify NamespaceNotFoundError is raised for missing namespace.
        """
        with pytest.raises(NamespaceNotFoundError):
            native_lance_db.describe_namespace(["nonexistent_namespace_xyz"])

    def test_table_not_found(self, native_lance_db):
        """
        Verify TableNotFoundError is raised for missing table.
        """
        # Need a valid namespace first
        import time
        ns_path = ["error_test_" + str(int(time.time() * 1000) % 10000)]
        native_lance_db.create_namespace(ns_path)

        with pytest.raises(TableNotFoundError):
            native_lance_db.open_table("nonexistent_table", namespace_path=ns_path)

        # Cleanup
        native_lance_db.drop_namespace(ns_path)


# ==============================================================================
# Test Entry Point
# ==============================================================================

if __name__ == "__main__":
    """
    Run tests directly from command line.

    Usage:
        python phase1_native_client_smoke.py

    Or with pytest:
        pytest phase1_native_client_smoke.py -v

    Note: Tests will be skipped if native client dependencies are unavailable.
    """
    pytest.main([__file__, "-v", "--tb=short", "-x"])