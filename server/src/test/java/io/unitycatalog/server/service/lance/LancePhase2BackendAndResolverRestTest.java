package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-BACKEND-001 SPI: verified by LanceExecutionBackend interface
    - P2-BACKEND-002~008: LancePhase2RequestMappingRestTest
    - P2-RESOLVE-001~006: LancePhase2DataPlaneMetadataUpdateRestTest
    - P2-RESOLVE-007~010: LancePhase2LegacyReadConfigRestTest
    Retain for legacy/non-Lance TEXT edge cases.""")
class LancePhase2BackendAndResolverRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-BACKEND-001 LanceExecutionBackend exposes only Phase 2 operations")
  void lanceExecutionBackendExposesOnlyPhase2Operations() throws Exception {
    Class<?> spi =
        Class.forName("io.unitycatalog.server.service.lance.backend.LanceExecutionBackend");

    assertThat(Arrays.stream(spi.getMethods()).map(Method::getName).collect(Collectors.toSet()))
        .contains(
            "query",
            "countRows",
            "stats",
            "insert",
            "mergeInsert",
            "update",
            "delete",
            "explainPlan",
            "analyzePlan",
            "create")
        .doesNotContain("buildIndex", "restoreTable", "renameTable", "commitTransaction");
  }

  @Test
  @DisplayName("P2-BACKEND-002 query command carries context, table, storage, and querySpec")
  void queryCommandCarriesRequiredFields() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postQueryExpectingArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", fullQuerySpecRequest()));

    assertThat(command.path("operation").asText()).isEqualTo("query");
    assertThat(command.path("tableId").asText()).isEqualTo(P2_ACTIVE_TABLE_ID);
    assertThat(command.path("storage").isObject()).isTrue();
    assertThat(command.path("querySpec").path("columns").toString()).contains("id", "text");
    assertThat(command.path("responseFormat").asText()).containsIgnoringCase("arrow");
  }

  @Test
  @DisplayName("P2-BACKEND-003 count and stats commands carry predicate, version, and storage")
  void countAndStatsCommandsCarryPredicateVersionAndStorage() throws Exception {
    createActiveTableFixture();

    JsonNode count =
        assertCommandEcho(
            postJson(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows",
                "{\"predicate\":\"id > 1\",\"version\":1}"));
    JsonNode stats =
        assertCommandEcho(
            postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{\"version\":1}"));

    assertThat(count.path("predicate").asText()).isEqualTo("id > 1");
    assertThat(count.path("version").asInt()).isEqualTo(1);
    assertThat(stats.path("version").asInt()).isEqualTo(1);
    assertThat(count.path("storage").isObject()).isTrue();
    assertThat(stats.path("storage").isObject()).isTrue();
  }

  @Test
  @DisplayName("P2-BACKEND-004 insert command carries Arrow input and materialization flag")
  void insertCommandCarriesArrowInputAndMaterializationFlag() throws Exception {
    createDeclaredTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertThat(command.path("operation").asText()).isEqualTo("insert");
    assertThat(command.path("inputData").asText()).containsIgnoringCase("arrow");
    assertThat(command.path("materializeDeclaredTable").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-BACKEND-005 merge_insert command preserves all protocol parameters")
  void mergeInsertCommandPreservesAllProtocolParameters() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/merge_insert",
                arrowSmallStreamFixture(),
                Map.of("x-lance-merge-options", mergeInsertAllParametersRequest())));

    assertThat(command.toString())
        .contains(
            "whenMatchedUpdateAllFilt",
            "whenNotMatchedBySourceDelete",
            "whenNotMatchedBySourceDeleteFilt",
            "timeout",
            "useIndex");
  }

  @Test
  @DisplayName("P2-BACKEND-006 update and delete commands preserve predicate and update map")
  void updateAndDeleteCommandsPreservePredicateAndUpdateMap() throws Exception {
    createActiveTableFixture();

    JsonNode update =
        assertCommandEcho(
            postJson(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/update",
                "{\"predicate\":\"id = 1\",\"updates\":{\"text\":\"updated\"}}"));
    JsonNode delete =
        assertCommandEcho(
            postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/delete", "{\"predicate\":\"id = 2\"}"));

    assertThat(update.path("predicate").asText()).isEqualTo("id = 1");
    assertThat(update.path("updates").toString()).contains("text", "updated");
    assertThat(delete.path("predicate").asText()).isEqualTo("id = 2");
  }

  @Test
  @DisplayName("P2-BACKEND-007 explain and analyze return 501 (LanceDB lacks native API)")
  void explainAndAnalyzeReturnNotImplemented() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse explain =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/explain_plan",
            "{\"query\":{\"filter\":\"id > 1\"},\"verbose\":true}");
    AggregatedHttpResponse analyze =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/analyze_plan",
            "{\"query\":{\"filter\":\"id > 1\"}}");

    assertLanceErrorShape(explain, 501);
    assertThat(json(explain).path("type").asText()).containsIgnoringCase("unimplemented");
    assertLanceErrorShape(analyze, 501);
    assertThat(json(analyze).path("type").asText()).containsIgnoringCase("unimplemented");
  }

  @Test
  @DisplayName("P2-BACKEND-008 create command carries mode, properties, and Arrow input")
  void createCommandCarriesModePropertiesAndArrowInput() throws Exception {
    createDeclaredTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_DECLARED_TABLE_ID + "/create",
                arrowSmallStreamFixture(),
                Map.of(
                    "x-lance-create-options",
                    "{\"mode\":\"create\",\"properties\":{\"p\":\"v\"}}")));

    assertThat(command.path("operation").asText()).isEqualTo("create");
    assertThat(command.toString()).contains("mode", "properties", "inputData");
  }

  @Test
  @DisplayName("P2-BACKEND-009 backend result is mapped without UC wrapper")
  void backendResultIsMappedWithoutUcWrapper() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}");

    assertSuccess(response);
    assertThat(response.contentUtf8()).doesNotContain("result", "data", "uc_wrapper");
    assertThat(json(response).has("count")).isTrue();
  }

  @Test
  @DisplayName("P2-BACKEND-010 backend warnings are preserved safely")
  void backendWarningsArePreservedSafely() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats",
            "{}",
            Map.of("x-lance-fake-warning", "safe warning"));

    assertSuccess(response);
    assertThat(json(response).path("warnings").toString()).contains("safe warning");
    assertThat(response.contentUtf8()).doesNotContain("secret", "token");
  }

  @Test
  @DisplayName("P2-RESOLVE-001 ACTIVE table is readable")
  void activeTableIsReadable() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");

    assertArrowResponse(response);
  }

  @Test
  @DisplayName("P2-RESOLVE-002 ACTIVE table is writable")
  void activeTableIsWritable() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
  }

  @Test
  @DisplayName("P2-RESOLVE-003 DECLARED table cannot be queried")
  void declaredTableCannotBeQueried() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/query", "{}");

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("conflict");
  }

  @Test
  @DisplayName("P2-RESOLVE-004 DECLARED table can be materialized by write")
  void declaredTableCanBeMaterializedByWrite() throws Exception {
    createDeclaredTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertThat(command.path("materializeDeclaredTable").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P2-RESOLVE-005 DEREGISTERED table rejects data operations")
  void deregisteredTableRejectsDataOperations() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/deregister", "{\"delete_physical_data\":false}"));

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");

    assertThat(response.status().code()).isIn(404, 409);
  }

  @Test
  @DisplayName("P2-RESOLVE-006 DROPPED table rejects data operations")
  void droppedTableRejectsDataOperations() throws Exception {
    createDeclaredTableFixture();
    assertSuccess(
        postJson("/v1/table/" + P2_DECLARED_TABLE_ID + "/drop", "{\"mode\":\"metadata_only\"}"));

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertThat(response.status().code()).isIn(404, 409);
  }

  @Test
  @DisplayName("P2-RESOLVE-007 legacy query is rejected by default")
  void legacyQueryIsRejectedByDefault() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_LEGACY_TABLE_ID + "/query", "{}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText()).containsIgnoringCase("legacy");
  }

  @Test
  @DisplayName("P2-RESOLVE-008 legacy read cannot be enabled by request header bypass")
  void legacyReadCannotBeEnabledByRequestHeaderBypass() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    // Header bypass has been removed per design Section 6.3/8.3.
    // Legacy reads must be enabled via server property lance.execution.legacy-read-enabled.
    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_LEGACY_TABLE_ID + "/query",
            "{}",
            Map.of("x-lance-legacy-read-enabled", "true"));

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText()).containsIgnoringCase("disabled");
    assertThat(response.contentUtf8()).doesNotContain("command");
  }

  @Test
  @DisplayName("P2-RESOLVE-009 legacy writes are rejected")
  void legacyWritesAreRejected() throws Exception {
    createUcCatalogAndSchema();
    createLegacyLanceTable();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_LEGACY_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText()).containsIgnoringCase("migrate");
  }

  @Test
  @DisplayName("P2-RESOLVE-010 non-Lance TEXT table is not treated as Lance")
  void nonLanceTextTableIsNotTreatedAsLance() throws Exception {
    createUcCatalogAndSchema();
    createNonLanceTextTable();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_NON_LANCE_TEXT_TABLE_ID + "/query", "{}");

    assertThat(response.status().code()).isIn(404, 501);
    assertThat(response.contentUtf8()).doesNotContain("legacyBridge\":true");
  }
}
