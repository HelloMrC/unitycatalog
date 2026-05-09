package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Tag("lance-nightly")
@Disabled(
    "Enable in nightly after real Lance worker and official connector environments are available.")
class LancePhase2EcosystemSmokeTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-CLIENT-003 Python vector query filter and withRowId smoke")
  void pythonVectorQueryFilterAndWithRowIdSmoke() throws Exception {
    AggregatedHttpResponse response =
        postJson(
            "/admin/smoke/python",
            "{\"scenario\":\"vector-filter-rowid\",\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\"}");

    assertSuccess(response);
    assertThat(json(response).path("resultShape").toString()).contains("row_id", "distance");
  }

  @Test
  @DisplayName("P2-CLIENT-004 Java client query count stats smoke")
  void javaClientQueryCountStatsSmoke() throws Exception {
    AggregatedHttpResponse response =
        postJson(
            "/admin/smoke/java",
            "{\"scenario\":\"query-count-stats\",\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\"}");

    assertSuccess(response);
    assertThat(json(response).path("authHeaderCompatible").asBoolean()).isTrue();
    assertThat(json(response).path("tableIdentifierCompatible").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-CLIENT-005 Rust client query smoke")
  void rustClientQuerySmoke() throws Exception {
    AggregatedHttpResponse response =
        postJson(
            "/admin/smoke/rust",
            "{\"scenario\":\"query\",\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\"}");

    assertSuccess(response);
    assertThat(json(response).path("queryOk").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-CLIENT-006 official clients recognize backend disabled and permission errors")
  void officialClientsRecognizeErrors() throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/client-errors", "{\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\"}");

    assertSuccess(response);
    assertThat(json(response).path("backendDisabledRecognized").asBoolean()).isTrue();
    assertThat(json(response).path("permissionDeniedRecognized").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-SPARK-001 official Spark catalog configuration is used")
  void sparkOfficialCatalogConfigurationIsUsed() throws Exception {
    AggregatedHttpResponse response = postJson("/admin/smoke/spark", "{\"scenario\":\"config\"}");

    assertSuccess(response);
    assertThat(json(response).path("officialConfigUsed").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-SPARK-002 Spark create or declared materialization activates table")
  void sparkCreateOrDeclaredMaterializationActivatesTable() throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/spark", "{\"scenario\":\"create-materialize\"}");

    assertSuccess(response);
    assertThat(json(response).path("state").asText()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("P2-SPARK-003 Spark INSERT updates version and stats")
  void sparkInsertUpdatesVersionAndStats() throws Exception {
    AggregatedHttpResponse response = postJson("/admin/smoke/spark", "{\"scenario\":\"insert\"}");

    assertSuccess(response);
    assertThat(json(response).path("version").isIntegralNumber()).isTrue();
    assertThat(json(response).path("statsUpdated").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-SPARK-004 Spark SELECT returns expected rows")
  void sparkSelectReturnsExpectedRows() throws Exception {
    AggregatedHttpResponse response = postJson("/admin/smoke/spark", "{\"scenario\":\"select\"}");

    assertSuccess(response);
    assertThat(json(response).path("rowCount").asInt()).isGreaterThan(0);
  }

  @Test
  @DisplayName("P2-SPARK-005 Spark UPDATE DELETE smoke is supported or explicitly skipped")
  void sparkUpdateDeleteSmokeIsSupportedOrSkipped() throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/spark", "{\"scenario\":\"update-delete\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isIn("PASSED", "SKIPPED");
  }

  @Test
  @DisplayName("P2-SPARK-006 Spark multi-level namespace does not degrade")
  void sparkMultiLevelNamespaceDoesNotDegrade() throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/spark", "{\"scenario\":\"multi-namespace\"}");

    assertSuccess(response);
    assertThat(json(response).path("tableId").asText()).contains("prod", "team_a");
  }

  @Test
  @DisplayName("P2-SPARK-007 Spark auth and context headers pass through")
  void sparkAuthAndContextHeadersPassThrough() throws Exception {
    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/admin/smoke/spark",
            "{\"scenario\":\"auth-context\"}",
            Map.of("x-lance-tenant-id", "tenant-a"));

    assertSuccess(response);
    assertThat(json(response).path("context").toString()).contains("tenant-a");
  }

  @Test
  @DisplayName("P2-SPARK-008 Spark recognizes permission and backend errors")
  void sparkRecognizesPermissionAndBackendErrors() throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/spark", "{\"scenario\":\"error-semantics\"}");

    assertSuccess(response);
    assertThat(json(response).path("permissionDeniedRecognized").asBoolean()).isTrue();
    assertThat(json(response).path("backendDisabledRecognized").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-RAY-001 Ray read_lance can read through UC endpoint")
  void rayReadLanceCanReadThroughUcEndpoint() throws Exception {
    assertConnectorScenario("ray", "read_lance");
  }

  @Test
  @DisplayName("P2-RAY-002 Ray write_lance create succeeds")
  void rayWriteLanceCreateSucceeds() throws Exception {
    assertConnectorScenario("ray", "write_create");
  }

  @Test
  @DisplayName("P2-RAY-003 Ray append or overwrite updates metadata")
  void rayAppendOrOverwriteUpdatesMetadata() throws Exception {
    assertConnectorScenario("ray", "append-overwrite");
  }

  @Test
  @DisplayName("P2-RAY-004 Ray filter and columns pushdown smoke")
  void rayFilterAndColumnsPushdownSmoke() throws Exception {
    assertConnectorScenario("ray", "filter-columns");
  }

  @Test
  @DisplayName("P2-RAY-005 Ray multi-segment table id maps correctly")
  void rayMultiSegmentTableIdMapsCorrectly() throws Exception {
    assertConnectorScenario("ray", "multi-segment-table-id");
  }

  @Test
  @DisplayName("P2-RAY-006 Ray storage_options can access object storage")
  void rayStorageOptionsCanAccessObjectStorage() throws Exception {
    assertConnectorScenario("ray", "storage-options");
  }

  @Test
  @DisplayName("P2-RAY-007 Ray distributed execution does not corrupt UC metadata")
  void rayDistributedExecutionDoesNotCorruptUcMetadata() throws Exception {
    assertConnectorScenario("ray", "distributed-metadata");
  }

  @Test
  @DisplayName("P2-LOCAL-001 UC resolves metadata and credentials for local engines")
  void ucResolvesMetadataAndCredentialsForLocalEngines() throws Exception {
    assertConnectorScenario("local", "resolve-metadata-credentials");
  }

  @Test
  @DisplayName("P2-LOCAL-002 DuckDB attach/read smoke")
  void duckDbAttachReadSmoke() throws Exception {
    assertConnectorScenario("local", "duckdb-attach-read");
  }

  @Test
  @DisplayName("P2-LOCAL-003 DuckDB SQL smoke")
  void duckDbSqlSmoke() throws Exception {
    assertConnectorScenario("local", "duckdb-sql");
  }

  @Test
  @DisplayName("P2-LOCAL-004 DuckDB vector and FTS smoke")
  void duckDbVectorAndFtsSmoke() throws Exception {
    assertConnectorScenario("local", "duckdb-vector-fts");
  }

  @Test
  @DisplayName("P2-LOCAL-005 Pandas DataFrame smoke")
  void pandasDataFrameSmoke() throws Exception {
    assertConnectorScenario("local", "pandas-dataframe");
  }

  @Test
  @DisplayName("P2-LOCAL-006 PyArrow table smoke")
  void pyArrowTableSmoke() throws Exception {
    assertConnectorScenario("local", "pyarrow-table");
  }

  @Test
  @DisplayName("P2-LOCAL-007 expired credentials refresh or fail safely")
  void expiredCredentialsRefreshOrFailSafely() throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/local", "{\"scenario\":\"expired-credentials\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isIn("REFRESHED", "CONTROLLED_FAILURE");
  }

  private void assertConnectorScenario(String connector, String scenario) throws Exception {
    AggregatedHttpResponse response =
        postJson("/admin/smoke/" + connector, "{\"scenario\":\"" + scenario + "\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isIn("PASSED", "SKIPPED");
  }
}
