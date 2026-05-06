package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("Enable after Lance Phase 2 write endpoints and metadata update hooks are implemented.")
class LancePhase2DataWriteRestTest extends BaseLancePhase2RestTest {

  @Test
  @DisplayName("P2-DATA-020 insert ACTIVE returns transaction metadata")
  void insertActiveReturnsTransactionMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    assertWriteResultHasTransactionMetadata(json(response));
  }

  @Test
  @DisplayName("P2-DATA-021 insert DECLARED materializes table")
  void insertDeclaredMaterializesTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    assertThat(json(response).path("state").asText()).isEqualTo("ACTIVE");
    assertThat(json(response).path("is_only_declared").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-DATA-022 create DECLARED materializes table")
  void createDeclaredMaterializesTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/create", arrowSmallStreamFixture());

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("tableUri").asText()).isNotBlank();
    assertThat(body.path("version").isIntegralNumber()).isTrue();
  }

  @Test
  @DisplayName("P2-DATA-023 merge_insert ACTIVE returns row counts")
  void mergeInsertActiveReturnsRowCounts() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/merge_insert", arrowSmallStreamFixture());

    assertSuccess(response);
    assertThat(json(response).has("updatedRows")).isTrue();
    assertThat(json(response).has("insertedRows")).isTrue();
    assertThat(json(response).has("deletedRows")).isTrue();
  }

  @Test
  @DisplayName("P2-DATA-024 merge_insert preserves all MergeInsert parameters")
  void mergeInsertPreservesAllMergeInsertParameters() throws Exception {
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
  @DisplayName("P2-DATA-025 update returns updatedRows and metadata")
  void updateReturnsUpdatedRowsAndMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/update",
            "{\"predicate\":\"id = 1\",\"updates\":{\"text\":\"updated\"}}");

    assertSuccess(response);
    assertThat(json(response).path("updatedRows").isIntegralNumber()).isTrue();
    assertWriteResultHasTransactionMetadata(json(response));
  }

  @Test
  @DisplayName("P2-DATA-026 delete returns deletedRows and metadata")
  void deleteReturnsDeletedRowsAndMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/delete", "{\"predicate\":\"id = 1\"}");

    assertSuccess(response);
    assertThat(json(response).path("deletedRows").isIntegralNumber()).isTrue();
    assertWriteResultHasTransactionMetadata(json(response));
  }

  @Test
  @DisplayName("P2-DATA-027 write is not automatically retried without idempotency key")
  void writeIsNotAutomaticallyRetriedWithoutIdempotencyKey() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-worker-error", "503-after-body"));

    assertThat(response.status().code()).isIn(503, 504);
    assertThat(json(response).path("retryAttempted").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-DATA-028 idempotent write forwards idempotency key hash")
  void idempotentWriteForwardsIdempotencyKeyHash() throws Exception {
    createActiveTableFixture();

    JsonNode command =
        assertCommandEcho(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
                arrowSmallStreamFixture(),
                Map.of("Idempotency-Key", "phase2-idempotent-write")));

    assertThat(command.path("idempotencyKeyHash").asText()).isNotBlank();
    assertThat(command.toString()).doesNotContain("phase2-idempotent-write");
  }

  @Test
  @DisplayName("P2-DATA-029 returned version cannot move metadata backwards")
  void returnedVersionCannotMoveMetadataBackwards() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-version", "0"));

    assertSuccess(response);
    assertThat(json(response).path("metadataVersionUpdated").asBoolean()).isFalse();
    assertThat(json(response).path("warnings").toString()).containsIgnoringCase("version");
  }

  @Test
  @DisplayName("P2-DATA-030 schema backfill follows worker result precedence")
  void schemaBackfillFollowsWorkerResultPrecedence() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-fake-schema-source", "worker"));

    assertSuccess(response);
    assertThat(json(response).path("arrow_schema_json").isTextual()).isTrue();
    assertThat(json(response).path("schemaSource").asText()).isEqualTo("worker");
  }

  private void assertWriteResultHasTransactionMetadata(JsonNode body) {
    assertThat(body.has("transactionId")).isTrue();
    assertThat(body.path("version").isIntegralNumber()).isTrue();
    assertThat(body.has("arrow_schema_json") || body.has("schema")).isTrue();
    assertThat(body.has("stats") || body.has("stats_json")).isTrue();
  }
}
