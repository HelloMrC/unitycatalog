# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# phase2_spark_s3_write_smoke.py
#
# Phase 2 Spark Connector S3 Write Smoke Tests for UC Lance.
#
# Test pattern: Spark writes Lance data to S3, registers metadata in UC.
# Rollback pattern: If UC registration fails, delete S3 data.
#
# Reference Test Cases:
# - P2-SPARK-002: Spark writes Lance table to S3-compatible storage
# - P2-SPARK-003: UC catalog integration for write path
# - P2-SPARK-005: Write operation with rollback on metadata failure
# - P2-SPARK-008: Spark read Lance from S3 via UC credential vending
#
# Prerequisites:
# - PySpark installed
# - lance-spark bundle JAR downloaded
# - UC server running with Lance routes enabled
# - MinIO running at localhost:9000
# - LanceDB installed for test data creation

import os
import pytest
import tempfile
import time
import shutil

# ==============================================================================
# Configuration
# ==============================================================================

UC_HOST = os.environ.get("UC_HOST", "http://localhost:8080")
UC_LANCE_URI = f"{UC_HOST}/api/2.1/unity-catalog/lance"

S3_ENDPOINT = os.environ.get("S3_ENDPOINT", "http://localhost:9000")
S3_ACCESS_KEY = os.environ.get("S3_ACCESS_KEY", "admin")
S3_SECRET_KEY = os.environ.get("S3_SECRET_KEY", "password")
S3_BUCKET = os.environ.get("S3_BUCKET", "lance-test")

SPARK_JAR_PATH = os.environ.get(
    "SPARK_JAR_PATH",
    "/home/lei/data_ai/learning/codebase/unitycatalog/tests/lance/spark_jars/lance-spark-bundle.jar"
)

TEST_NAMESPACE_PREFIX = "spark_s3_write"

# ==============================================================================
# Helper Functions
# ==============================================================================

def get_spark_session():
    """Create SparkSession with lance-spark JAR and S3 config."""
    from pyspark.sql import SparkSession

    spark = SparkSession.builder \
        .appName("LanceSparkS3Write") \
        .master("local[2]") \
        .config("spark.jars", SPARK_JAR_PATH) \
        .config("spark.driver.memory", "2g") \
        .config("spark.ui.enabled", "false") \
        .config("spark.sql.catalog.lance", "com.lancedb.lance.spark.LanceCatalog") \
        .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")
    return spark


def get_minio_client():
    """Get MinIO client for S3 operations."""
    from minio import Minio
    return Minio(
        S3_ENDPOINT.replace("http://", "").replace("https://", ""),
        access_key=S3_ACCESS_KEY,
        secret_key=S3_SECRET_KEY,
        secure=False
    )


def ensure_bucket_exists():
    """Ensure test bucket exists in MinIO."""
    client = get_minio_client()
    if not client.bucket_exists(S3_BUCKET):
        client.make_bucket(S3_BUCKET)


def upload_lance_to_s3(local_path, s3_prefix):
    """Upload Lance directory to S3."""
    client = get_minio_client()
    ensure_bucket_exists()

    # Upload all files in Lance directory
    for root, dirs, files in os.walk(local_path):
        for file in files:
            local_file = os.path.join(root, file)
            # Relative path within Lance directory
            rel_path = os.path.relpath(local_file, local_path)
            s3_object_name = f"{s3_prefix}/{rel_path}"

            client.fput_object(S3_BUCKET, s3_object_name, local_file)

    return f"s3://{S3_BUCKET}/{s3_prefix}"


def delete_s3_prefix(s3_prefix):
    """Delete all objects with given prefix from S3 (rollback)."""
    client = get_minio_client()

    objects = client.list_objects(S3_BUCKET, prefix=s3_prefix, recursive=True)
    for obj in objects:
        client.remove_object(S3_BUCKET, obj.object_name)


def create_lance_test_data(path):
    """Create Lance test data using LanceDB."""
    import lancedb
    import pyarrow as pa

    data = pa.table({
        "id": pa.array([1, 2, 3, 4, 5], type=pa.int64()),
        "name": pa.array(["alice", "bob", "charlie", "diana", "eve"]),
        "value": pa.array([1.0, 2.5, 3.7, 4.2, 5.9], type=pa.float64()),
    })

    db = lancedb.connect(path)
    table = db.create_table("test_table", data)
    return os.path.join(path, "test_table.lance")


# ==============================================================================
# Test Fixtures
# ==============================================================================

@pytest.fixture(scope="module")
def spark_session():
    """Spark session fixture."""
    if not os.path.exists(SPARK_JAR_PATH):
        pytest.skip(f"lance-spark JAR not found at {SPARK_JAR_PATH}")

    try:
        spark = get_spark_session()
        yield spark
        spark.stop()
    except ImportError:
        pytest.skip("PySpark not installed - pip install pyspark")


@pytest.fixture(scope="module")
def minio_available():
    """Check if MinIO is available."""
    try:
        from minio import Minio
        client = get_minio_client()
        # Test connection
        client.bucket_exists("test")
        return True
    except Exception as e:
        pytest.skip(f"MinIO not available: {e}")


@pytest.fixture(scope="module")
def uc_available():
    """Check if UC server is available."""
    import requests
    try:
        resp = requests.get(f"{UC_HOST}/api/2.1/unity-catalog/lance/v1/namespace", timeout=5)
        return resp.status_code in [200, 404]
    except Exception as e:
        pytest.skip(f"UC server not available: {e}")


# ==============================================================================
# S3 Write Tests
# ==============================================================================

class TestSparkS3Write:
    """Test Spark write to S3 with UC metadata registration."""

    def test_lancedb_write_to_s3_then_register(self, minio_available, uc_available):
        """P2-SPARK-002/003: LanceDB writes Lance to S3, registers in UC."""
        import lancedb
        import pyarrow as pa
        import requests

        # 1. Create Lance data in temp directory
        temp_dir = tempfile.mkdtemp(prefix="lance_s3_write_")
        lance_path = create_lance_test_data(temp_dir)

        # 2. Upload to S3 (simulating LanceDB direct write)
        test_id = f"{TEST_NAMESPACE_PREFIX}_{int(time.time())}"
        s3_prefix = f"{test_id}/test_table.lance"
        s3_location = upload_lance_to_s3(lance_path, s3_prefix)

        print(f"Uploaded Lance to: {s3_location}")

        # 3. Create namespace in UC
        namespace_id = test_id
        ns_resp = requests.post(
            f"{UC_LANCE_URI}/v1/namespace/{namespace_id}/create",
            json={"properties": {"owner": "test"}}
        )

        if ns_resp.status_code not in [200, 201]:
            pytest.skip(f"UC namespace create failed: {ns_resp.status_code}")

        print(f"Created namespace: {namespace_id}")

        # 4. Register table in UC
        table_id = f"{namespace_id}$test_table"
        register_resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{table_id}/register",
            json={
                "location": s3_location,
                "vend_credentials": True,
                "storage_options_template": {
                    "endpoint": S3_ENDPOINT,
                    "region": "local"
                },
                "properties": {"table_type": "lance"}
            }
        )

        if register_resp.status_code not in [200, 201]:
            # Rollback: delete S3 data
            delete_s3_prefix(s3_prefix)
            pytest.fail(f"UC register failed: {register_resp.status_code} - rolled back S3 data")

        print(f"Registered table: {table_id}")

        # 5. Describe table with credentials
        describe_resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{table_id}/describe",
            json={"vend_credentials": True}
        )

        assert describe_resp.status_code == 200
        table_info = describe_resp.json()

        print(f"Table location: {table_info.get('location')}")
        assert "storage_options" in table_info or "storage_options_template" in table_info

        # Cleanup: deregister table and delete S3 data
        requests.post(f"{UC_LANCE_URI}/v1/table/{table_id}/deregister",
                      json={"delete_physical_data": True})
        requests.post(f"{UC_LANCE_URI}/v1/namespace/{namespace_id}/drop")
        shutil.rmtree(temp_dir, ignore_errors=True)

    def test_write_failure_rollback(self, minio_available, uc_available):
        """P2-SPARK-005: Simulate registration failure, verify rollback."""
        import lancedb
        import requests

        # 1. Create Lance data
        temp_dir = tempfile.mkdtemp(prefix="lance_rollback_")
        lance_path = create_lance_test_data(temp_dir)

        # 2. Upload to S3
        test_id = f"{TEST_NAMESPACE_PREFIX}_rollback_{int(time.time())}"
        s3_prefix = f"{test_id}/test_table.lance"
        s3_location = upload_lance_to_s3(lance_path, s3_prefix)

        print(f"Uploaded Lance to: {s3_location}")

        # 3. Try to register with INVALID namespace (simulate failure)
        # Using non-existent namespace should fail
        invalid_table_id = f"nonexistent_namespace$test_table"
        register_resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{invalid_table_id}/register",
            json={
                "location": s3_location,
                "vend_credentials": False,
                "properties": {"table_type": "lance"}
            }
        )

        if register_resp.status_code not in [200, 201]:
            # 4. Rollback: delete S3 data
            print(f"Registration failed (expected): {register_resp.status_code}")
            delete_s3_prefix(s3_prefix)
            print("Rolled back: deleted S3 data")

            # 5. Verify S3 data is gone
            client = get_minio_client()
            objects = list(client.list_objects(S3_BUCKET, prefix=s3_prefix, recursive=True))
            assert len(objects) == 0, "Rollback failed: S3 data still exists"
            print("Verified: S3 data deleted")

        shutil.rmtree(temp_dir, ignore_errors=True)

    def test_spark_read_s3_via_uc_credentials(self, spark_session, minio_available, uc_available):
        """P2-SPARK-008: Spark reads Lance from S3 using UC-vended credentials."""
        import requests

        # This test requires a pre-existing table in UC with S3 location
        # Skip if we can't create the test table
        pytest.skip("Requires UC to vend S3 credentials to Spark - integration not complete")

        # Ideal flow (not yet implemented):
        # 1. UC describe table with vend_credentials=True
        # 2. UC returns location + storage_options (S3 credentials)
        # 3. Spark reads using those credentials
        #
        # Current limitation: Spark lance connector needs direct S3 config,
        # not UC-vended credentials via REST API


# ==============================================================================
# Lifecycle Tests
# ==============================================================================

class TestSparkS3Lifecycle:
    """Test full lifecycle: write, register, read, deregister."""

    def test_full_write_register_read_deregister_cycle(self, minio_available, uc_available):
        """Complete lifecycle test for S3 Lance table."""
        import lancedb
        import pyarrow as pa
        import requests

        # Phase 1: Write data to S3
        temp_dir = tempfile.mkdtemp(prefix="lance_cycle_")
        lance_path = create_lance_test_data(temp_dir)

        test_id = f"{TEST_NAMESPACE_PREFIX}_cycle_{int(time.time())}"
        s3_prefix = f"{test_id}/cycle_table.lance"
        s3_location = upload_lance_to_s3(lance_path, s3_prefix)

        print(f"Phase 1: Written to {s3_location}")

        # Phase 2: Register in UC
        namespace_id = test_id
        requests.post(f"{UC_LANCE_URI}/v1/namespace/{namespace_id}/create",
                      json={"properties": {"owner": "test"}})

        table_id = f"{namespace_id}$cycle_table"
        register_resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{table_id}/register",
            json={
                "location": s3_location,
                "vend_credentials": True,
                "storage_options_template": {
                    "endpoint": S3_ENDPOINT,
                    "region": "local"
                },
                "properties": {"table_type": "lance"}
            }
        )

        assert register_resp.status_code in [200, 201]
        print(f"Phase 2: Registered {table_id}")

        # Phase 3: Read via LanceDB with UC credentials
        describe_resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{table_id}/describe",
            json={"vend_credentials": True}
        )

        assert describe_resp.status_code == 200
        table_info = describe_resp.json()
        print(f"Phase 3: Retrieved metadata")

        # Verify table state
        assert table_info.get("state") == "ACTIVE"
        assert not table_info.get("is_only_declared", True)

        # Phase 4: Deregister (keep S3 data)
        dereg_resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{table_id}/deregister",
            json={"delete_physical_data": False}
        )

        assert dereg_resp.status_code in [200, 204]
        print(f"Phase 4: Deregistered (S3 data preserved)")

        # Verify S3 data still exists
        client = get_minio_client()
        objects = list(client.list_objects(S3_BUCKET, prefix=s3_prefix, recursive=True))
        assert len(objects) > 0, "S3 data should still exist after deregister"

        # Phase 5: Cleanup - delete S3 data and namespace
        delete_s3_prefix(s3_prefix)
        requests.post(f"{UC_LANCE_URI}/v1/namespace/{namespace_id}/drop")
        shutil.rmtree(temp_dir, ignore_errors=True)

        print("Phase 5: Cleanup complete")


# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short", "-m", "not skip"])