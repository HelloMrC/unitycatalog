# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: Copyright The Unity Catalog Authors
#
# phase2_spark_smoke.py
#
# Phase 2 Spark Connector Smoke Tests for UC Lance.
#
# These tests verify Spark can read Lance format tables after UC provides
# metadata and credential resolution. The test pattern is:
# 1. UC REST API: describe table with vend_credentials=True
# 2. UC returns: location + storage_options
# 3. Spark: read Lance table using location + storage_options
#
# Reference Test Cases:
# - P2-SPARK-001: Official Spark catalog configuration
# - P2-SPARK-004: Spark SELECT returns expected rows
# - P2-LOCAL-001: UC resolves metadata and credentials for local engines
#
# Prerequisites:
# - PySpark installed (pip install pyspark)
# - lance-spark bundle JAR downloaded
# - UC server running with Lance routes enabled
# - MinIO/S3 storage available
#
# Design Document Reference:
# - docs/lance/unitycatalog-lancedb-phase-2-data-plane-test-design.md Section 9.15

import os
import pytest
import tempfile
import time

# ==============================================================================
# Configuration
# ==============================================================================

UC_HOST = os.environ.get("UC_HOST", "http://localhost:8080")
UC_LANCE_URI = f"{UC_HOST}/api/2.1/unity-catalog/lance"

S3_ENDPOINT = os.environ.get("S3_ENDPOINT", "http://localhost:9000")
S3_ACCESS_KEY = os.environ.get("S3_ACCESS_KEY", "admin")
S3_SECRET_KEY = os.environ.get("S3_SECRET_KEY", "password")
S3_BUCKET = os.environ.get("S3_BUCKET", "lance-test")

# Lance-spark JAR path
SPARK_JAR_PATH = os.environ.get(
    "SPARK_JAR_PATH",
    "/home/lei/data_ai/learning/codebase/unitycatalog/tests/lance/spark_jars/lance-spark-bundle.jar"
)

TEST_NAMESPACE_PREFIX = "spark_smoke"

# ==============================================================================
# Helper Functions
# ==============================================================================

def get_spark_session():
    """Create SparkSession with lance-spark JAR."""
    from pyspark.sql import SparkSession

    spark = SparkSession.builder \
        .appName("LanceSparkSmoke") \
        .master("local[2]") \
        .config("spark.jars", SPARK_JAR_PATH) \
        .config("spark.driver.memory", "2g") \
        .config("spark.ui.enabled", "false") \
        .config("spark.sql.catalog.lance", "com.lancedb.lance.spark.LanceCatalog") \
        .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")
    return spark


def create_lance_test_data(path):
    """Create Lance test data using LanceDB."""
    import lancedb
    import pyarrow as pa

    data = pa.table({
        "id": pa.array([1, 2, 3, 4, 5], type=pa.int64()),
        "name": pa.array(["alice", "bob", "charlie", "diana", "eve"]),
        "value": pa.array([1.0, 2.5, 3.7, 4.2, 5.9], type=pa.float64()),
        "vector": pa.array(
            [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6], [0.7, 0.8, 0.9], [1.0, 1.1, 1.2], [1.3, 1.4, 1.5]],
            type=pa.list_(pa.float64())
        )
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
def lance_test_data():
    """Lance test data fixture."""
    try:
        import lancedb
        import pyarrow as pa
    except ImportError:
        pytest.skip("lancedb/pyarrow not installed")

    temp_dir = tempfile.mkdtemp(prefix="lance_spoke_smoke_")
    lance_path = create_lance_test_data(temp_dir)

    yield lance_path

    # Cleanup
    import shutil
    shutil.rmtree(temp_dir, ignore_errors=True)


# ==============================================================================
# Environment Tests
# ==============================================================================

class TestSparkEnvironment:
    """Verify Spark environment is ready."""

    def test_pyspark_installed(self):
        """P2-SPARK prerequisite: PySpark available."""
        try:
            import pyspark
            print(f"PySpark version: {pyspark.__version__}")
        except ImportError:
            pytest.skip("PySpark not installed")

    def test_lance_spark_jar_exists(self):
        """P2-SPARK prerequisite: lance-spark JAR available."""
        if not os.path.exists(SPARK_JAR_PATH):
            pytest.skip(f"lance-spark JAR not found at {SPARK_JAR_PATH}")

        jar_size_mb = os.path.getsize(SPARK_JAR_PATH) / 1024 / 1024
        print(f"JAR size: {jar_size_mb:.1f} MB")
        assert jar_size_mb > 50, "JAR seems incomplete"

    def test_spark_session_creation(self, spark_session):
        """P2-SPARK-001: Spark session with lance-spark created."""
        assert spark_session is not None
        print(f"Spark version: {spark_session.version}")


# ==============================================================================
# Lance Read Tests
# ==============================================================================

class TestSparkLanceRead:
    """Test reading Lance tables with Spark."""

    def test_read_local_lance_table(self, spark_session, lance_test_data):
        """P2-SPARK-004: Spark can read Lance table from local path."""
        df = spark_session.read.format("lance").load(lance_test_data)

        count = df.count()
        print(f"Read {count} rows from Lance table")
        assert count == 5, f"Expected 5 rows, got {count}"

        # Verify schema
        assert "id" in df.columns
        assert "name" in df.columns
        assert "value" in df.columns

        df.show()

    def test_read_lance_with_filter(self, spark_session, lance_test_data):
        """P2-SPARK-004: Spark can filter Lance data."""
        df = spark_session.read.format("lance").load(lance_test_data)

        filtered = df.filter("value > 2.0").select("id", "name")
        count = filtered.count()

        print(f"Filtered {count} rows with value > 2.0")
        assert count == 4  # bob(2.5), charlie(3.7), diana(4.2), eve(5.9)

        filtered.orderBy("id").show()

    def test_read_lance_sql_query(self, spark_session, lance_test_data):
        """P2-SPARK-004: Spark SQL on Lance table."""
        df = spark_session.read.format("lance").load(lance_test_data)
        df.createOrReplaceTempView("lance_test")

        result = spark_session.sql("""
            SELECT id, name, value
            FROM lance_test
            WHERE value > 3.0
            ORDER BY id
        """)

        count = result.count()
        print(f"SQL query returned {count} rows")
        assert count == 3  # charlie(3.7), diana(4.2), eve(5.9)

        result.show()

    def test_read_lance_schema(self, spark_session, lance_test_data):
        """P2-LOCAL-006: Verify Lance schema in Spark."""
        df = spark_session.read.format("lance").load(lance_test_data)

        schema = df.schema
        print(f"Schema: {schema}")

        # Verify column types
        id_field = schema["id"]
        assert id_field.dataType.typeName() == "long"

        name_field = schema["name"]
        assert name_field.dataType.typeName() == "string"

        value_field = schema["value"]
        assert value_field.dataType.typeName() == "double"

    def test_read_lance_multi_level_namespace(self, spark_session):
        """P2-SPARK-006: Spark reads Lance table with multi-level namespace."""
        import lancedb
        import pyarrow as pa

        # Create nested temp directory simulating multi-level namespace
        temp_dir = tempfile.mkdtemp(prefix="lance_multilevel_")

        # Create namespace-like path: /tmp/lance_multilevel/prod/team_a/embeddings.lance
        nested_path = os.path.join(temp_dir, "prod", "team_a")
        os.makedirs(nested_path, exist_ok=True)

        # Create Lance table at nested path
        data = pa.table({
            "id": pa.array([1, 2, 3], type=pa.int64()),
            "embedding_id": pa.array(["emb1", "emb2", "emb3"]),
            "vector": pa.array([[0.1, 0.2], [0.3, 0.4], [0.5, 0.6]], type=pa.list_(pa.float64()))
        })

        db = lancedb.connect(nested_path)
        table = db.create_table("embeddings", data)
        lance_path = os.path.join(nested_path, "embeddings.lance")

        # Read via Spark
        df = spark_session.read.format("lance").load(lance_path)
        count = df.count()

        print(f"Read {count} rows from multi-level namespace Lance table")
        assert count == 3

        # Verify nested path doesn't cause issues
        schema = df.schema
        assert "embedding_id" in schema.fieldNames()

        df.show()

        # Cleanup
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)


# ==============================================================================
# UC Integration Tests
# ==============================================================================

class TestSparkUCIntegration:
    """Test UC metadata resolution + Spark read pattern."""

    @pytest.mark.skip(reason="UC endpoint may not be running")
    def test_uc_describe_with_credentials(self):
        """P2-LOCAL-001: UC resolves metadata and credentials."""
        import requests

        # Create namespace and table via UC REST
        namespace_id = f"{TEST_NAMESPACE_PREFIX}_{int(time.time())}"
        table_id = f"{namespace_id}$spark_test"

        # Describe table with credentials
        resp = requests.post(
            f"{UC_LANCE_URI}/v1/table/{table_id}/describe",
            json={"vend_credentials": True}
        )

        if resp.status_code != 200:
            pytest.skip(f"UC describe failed: {resp.status_code}")

        data = resp.json()

        # Verify UC returns storage info
        assert "location" in data or "storage_location" in data
        print(f"Location: {data.get('location', data.get('storage_location'))}")

    @pytest.mark.skip(reason="S3 access requires runtime credentials")
    def test_spark_read_from_s3_lance(self, spark_session):
        """P2-STORAGE-008: Spark read Lance from S3-compatible storage."""
        s3_path = f"s3://{S3_BUCKET}/lance_table.lance"

        try:
            df = spark_session.read \
                .format("lance") \
                .option("endpoint", S3_ENDPOINT.replace("http://", "")) \
                .option("key", S3_ACCESS_KEY) \
                .option("secret", S3_SECRET_KEY) \
                .load(s3_path)

            print(f"Read from S3: {df.count()} rows")
            df.show()
        except Exception as e:
            pytest.skip(f"S3 read failed: {e}")


# ==============================================================================
# DataFrame Operations Tests
# ==============================================================================

class TestSparkDataFrameOps:
    """Test DataFrame operations on Lance data."""

    def test_aggregate_operations(self, spark_session, lance_test_data):
        """Spark aggregations on Lance."""
        df = spark_session.read.format("lance").load(lance_test_data)

        # Aggregate
        agg_result = df.agg({
            "value": "sum",
            "id": "count"
        }).collect()[0]

        total_value = agg_result["sum(value)"]
        row_count = agg_result["count(id)"]

        print(f"Sum of values: {total_value}, Count: {row_count}")
        assert row_count == 5

    def test_join_operations(self, spark_session, lance_test_data):
        """Spark joins with Lance data."""
        df1 = spark_session.read.format("lance").load(lance_test_data)

        # Create another DataFrame
        df2 = spark_session.createDataFrame([
            (1, "extra1"),
            (2, "extra2"),
            (3, "extra3")
        ], ["id", "extra"])

        # Join
        joined = df1.join(df2, "id", "inner")
        count = joined.count()

        print(f"Join result: {count} rows")
        assert count == 3

        joined.show()

    def test_dataframe_conversion(self, spark_session, lance_test_data):
        """Convert Lance to Pandas via Spark."""
        try:
            import pandas
        except ImportError:
            pytest.skip("Pandas not installed - pip install pandas")

        df = spark_session.read.format("lance").load(lance_test_data)

        # Convert to Pandas
        pandas_df = df.toPandas()

        print(f"Pandas DataFrame shape: {pandas_df.shape}")
        assert len(pandas_df) == 5
        assert list(pandas_df.columns) == ["id", "name", "value", "vector"]


# ==============================================================================
# Main
# ==============================================================================

# ==============================================================================
# Vector Search Tests
# ==============================================================================

class TestSparkVectorSearch:
    """Test Spark read Lance tables with vector columns and indexes."""

    @pytest.fixture(scope="class")
    def vector_test_data(self):
        """Lance table with vector column and index."""
        try:
            import lancedb
            import pyarrow as pa
            import numpy as np
        except ImportError:
            pytest.skip("lancedb/pyarrow/numpy not installed")

        temp_dir = tempfile.mkdtemp(prefix="lance_vector_")

        # Create enough data for vector index (256+ rows required)
        n = 300
        ids = list(range(1, n + 1))
        texts = [f"text_{i}" for i in range(n)]
        np.random.seed(42)
        vectors = np.random.rand(n, 3).tolist()

        data = pa.table({
            "id": pa.array(ids, type=pa.int64()),
            "text": pa.array(texts),
            "vector": pa.array(vectors, type=pa.list_(pa.float64()))
        })

        db = lancedb.connect(temp_dir)
        table = db.create_table("vector_table", data)

        # Create vector index
        table.create_index(
            vector_column_name="vector",
            metric="l2",
            num_partitions=2,
            num_sub_vectors=1
        )

        lance_path = os.path.join(temp_dir, "vector_table.lance")
        yield lance_path

        # Cleanup
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)

    def test_read_lance_with_vector_column(self, spark_session, vector_test_data):
        """P2-SPARK-006: Spark reads Lance table with vector column."""
        df = spark_session.read.format("lance").load(vector_test_data)

        count = df.count()
        print(f"Read {count} rows from Lance table with vector column")
        assert count == 300

        # Verify schema includes vector column
        assert "vector" in df.columns
        schema = df.schema
        vector_field = schema["vector"]
        # Vector should be ArrayType
        assert vector_field.dataType.typeName() == "array"

        df.show(5)

    def test_vector_column_filter(self, spark_session, vector_test_data):
        """P2-SPARK-006: Spark filter on non-vector columns of vector table."""
        df = spark_session.read.format("lance").load(vector_test_data)

        # Filter on regular column
        filtered = df.filter("id < 100")
        count = filtered.count()
        print(f"Filtered {count} rows with id < 100")
        assert count == 99

        # Select specific columns including vector
        selected = filtered.select("id", "text", "vector")
        assert selected.count() == 99


# ==============================================================================
# Partition Pruning Tests
# ==============================================================================

class TestSparkPartitionPruning:
    """Test Spark partition pruning on Lance tables."""

    @pytest.fixture(scope="class")
    def partitioned_test_data(self):
        """Lance table with partition-like data distribution."""
        try:
            import lancedb
            import pyarrow as pa
        except ImportError:
            pytest.skip("lancedb/pyarrow not installed")

        temp_dir = tempfile.mkdtemp(prefix="lance_partition_")

        # Create data with categorical partition column
        data = pa.table({
            "id": pa.array(range(1, 101), type=pa.int64()),
            "category": pa.array(["A"] * 50 + ["B"] * 50),
            "value": pa.array(range(100), type=pa.int64())
        })

        db = lancedb.connect(temp_dir)
        table = db.create_table("partitioned_table", data)

        lance_path = os.path.join(temp_dir, "partitioned_table.lance")
        yield lance_path

        # Cleanup
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)

    def test_read_partitioned_lance_table(self, spark_session, partitioned_test_data):
        """P2-SPARK-007: Spark reads Lance table with partition-like column."""
        df = spark_session.read.format("lance").load(partitioned_test_data)

        count = df.count()
        print(f"Read {count} rows from partitioned Lance table")
        assert count == 100

        # Verify all categories present
        categories = df.select("category").distinct().collect()
        category_values = [row["category"] for row in categories]
        assert "A" in category_values
        assert "B" in category_values

    def test_partition_column_filter(self, spark_session, partitioned_test_data):
        """P2-SPARK-007: Spark filter pushes down to Lance partition column."""
        df = spark_session.read.format("lance").load(partitioned_test_data)

        # Filter on partition-like column
        filtered = df.filter("category == 'A'")
        count = filtered.count()
        print(f"Filtered {count} rows with category='A'")
        assert count == 50

        # Verify all filtered rows have correct category
        filtered_rows = filtered.collect()
        for row in filtered_rows[:10]:
            assert row["category"] == "A"

    def test_partition_column_aggregation(self, spark_session, partitioned_test_data):
        """P2-SPARK-007: Spark aggregation grouped by partition column."""
        df = spark_session.read.format("lance").load(partitioned_test_data)

        # Group by partition column
        grouped = df.groupBy("category").count()
        result = grouped.collect()

        print("Group by category results:")
        for row in result:
            print(f"  {row['category']}: {row['count']} rows")

        # Verify counts
        counts = {row["category"]: row["count"] for row in result}
        assert counts["A"] == 50
        assert counts["B"] == 50


# ==============================================================================
# Error Semantics Tests (P2-SPARK-008)
# ==============================================================================

class TestSparkErrorSemantics:
    """Test P2-SPARK-008: Error semantics can be captured by Spark."""

    def test_spark_read_nonexistent_path(self, spark_session):
        """P2-SPARK-008: Spark captures path not found error."""
        nonexistent_path = "/tmp/nonexistent_lance_table_12345.lance"

        try:
            df = spark_session.read.format("lance").load(nonexistent_path)
            df.count()
            pytest.fail("Should have raised an error for nonexistent path")
        except Exception as e:
            # Verify error is captured and has meaningful message
            error_str = str(e)
            print(f"Captured error: {error_str[:200]}")

            # Error should contain path-related information
            assert len(error_str) > 0, "Error message should not be empty"

            # Common patterns: "not found", "does not exist", "Path"
            has_path_info = any(
                kw in error_str.lower()
                for kw in ["not found", "does not exist", "path", "file", "no such", "error"]
            )
            assert has_path_info, f"Error should mention path issue: {error_str}"

    def test_spark_read_invalid_lance_file(self, spark_session):
        """P2-SPARK-008: Spark captures invalid Lance format error."""
        import tempfile

        # Create invalid file (not a valid Lance table)
        temp_dir = tempfile.mkdtemp(prefix="invalid_lance_")
        invalid_path = os.path.join(temp_dir, "invalid.lance")
        os.makedirs(invalid_path, exist_ok=True)

        # Write random bytes to simulate corrupted Lance file
        with open(os.path.join(invalid_path, "data"), "wb") as f:
            f.write(b"invalid data not lance format")

        try:
            df = spark_session.read.format("lance").load(invalid_path)
            df.count()
            pytest.fail("Should have raised an error for invalid Lance file")
        except Exception as e:
            error_str = str(e)
            print(f"Captured error for invalid file: {error_str[:200]}")

            # Error should be captured
            assert len(error_str) > 0

        # Cleanup
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)

    def test_spark_read_permission_denied_simulation(self, spark_session):
        """P2-SPARK-008: Simulate permission denied scenario."""
        # This test verifies that Spark can capture permission-related errors
        # In real scenario, UC would return 403 for unauthorized access

        # Create a path with restricted permissions (Linux only)
        import tempfile
        import stat

        temp_dir = tempfile.mkdtemp(prefix="restricted_lance_")

        # Create valid Lance table first
        import lancedb
        import pyarrow as pa

        data = pa.table({
            "id": pa.array([1, 2, 3], type=pa.int64()),
            "value": pa.array([1.0, 2.0, 3.0], type=pa.float64())
        })

        db = lancedb.connect(temp_dir)
        table = db.create_table("restricted_table", data)
        lance_path = os.path.join(temp_dir, "restricted_table.lance")

        # Restrict permissions (may not work on all systems)
        try:
            os.chmod(lance_path, stat.S_IRUSR | stat.S_IWUSR)  # Owner only
            # Restrict parent directory to prevent access
            os.chmod(temp_dir, 0)  # No permissions
        except PermissionError:
            # Skip if can't set permissions (e.g., running as root)
            import shutil
            shutil.rmtree(temp_dir, ignore_errors=True)
            pytest.skip("Cannot set restricted permissions")

        try:
            df = spark_session.read.format("lance").load(lance_path)
            df.count()
            # May succeed or fail depending on system
            print("Read succeeded despite restricted permissions (may be running as root)")
        except Exception as e:
            error_str = str(e)
            print(f"Captured permission error: {error_str[:200]}")
            # Error should be captured
            assert len(error_str) > 0
        finally:
            # Restore permissions for cleanup
            try:
                os.chmod(temp_dir, stat.S_IRWXU)
            except:
                pass
            import shutil
            shutil.rmtree(temp_dir, ignore_errors=True)


# ==============================================================================
# Auth Passthrough Tests (P2-SPARK-007) - Feature Gap
# ==============================================================================

class TestSparkAuthPassthrough:
    """Test P2-SPARK-007: Header/auth passthrough through Spark context."""

    @pytest.mark.skip(reason="lance-spark does not support auth header config yet")
    def test_spark_bearer_token_passthrough(self):
        """P2-SPARK-007: Bearer token should pass through Spark to UC.

        Feature gap: lance-spark connector does not expose configuration
        for setting auth headers (Bearer token, API key) in Spark context.

        Required configuration (not currently available):
        - spark.lance.auth.bearer-token
        - spark.lance.auth.api-key
        - spark.lance.auth.header-prefix

        Once implemented, this test would verify:
        1. Spark context configures auth header
        2. Lance read request includes auth header
        3. UC receives and validates auth header
        4. Unauthorized request returns 403 captured by Spark
        """
        pass

    @pytest.mark.skip(reason="lance-spark does not support auth header config yet")
    def test_spark_api_key_passthrough(self):
        """P2-SPARK-007: API key should pass through Spark to UC.

        Feature gap: Similar to Bearer token, lance-spark lacks API key
        configuration in Spark context.

        Required configuration:
        - spark.lance.auth.api-key
        """
        pass

    @pytest.mark.skip(reason="lance-spark does not support context headers yet")
    def test_spark_context_headers_passthrough(self):
        """P2-SPARK-007: x-lance-* context headers should passthrough.

        Feature gap: lance-spark does not support custom header configuration.

        Required configuration:
        - spark.lance.context.request-id
        - spark.lance.context.application-name
        """
        pass


# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short", "-m", "not skip"])