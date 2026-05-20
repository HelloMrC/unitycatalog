package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase3")
class LancePhase3TransactionRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  // ========== P3-SYNC-004: syncTransaction 测试 ==========

  @Test
  @DisplayName("P3-SYNC-004-01: syncTransaction writes transaction metadata")
  void syncTransactionWritesTransactionMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse syncResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-001\",\"status\":\"RUNNING\","
                + "\"actions\":[{\"type\":\"update\"}],"
                + "\"created_by\":\"external-worker\"}");

    assertSuccess(syncResponse);
    JsonNode synced = json(syncResponse);
    assertThat(synced.path("transaction_key").asText()).isEqualTo("tx-001");
    assertThat(synced.path("status").asText()).isEqualTo("RUNNING");
    assertThat(synced.path("actions").isArray()).isTrue();
    assertThat(synced.path("created_by").asText()).isEqualTo("external-worker");

    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/describe",
            "{\"transaction_key\":\"tx-001\"}");

    assertSuccess(describeResponse);
    JsonNode tx = json(describeResponse);
    assertThat(tx.path("transaction_key").asText()).isEqualTo("tx-001");
    assertThat(tx.path("status").asText()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("P3-SYNC-004-02: syncTransaction upserts existing transaction")
  void syncTransactionUpsertsExistingTransaction() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-002\",\"status\":\"QUEUED\"}"));

    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-002\",\"status\":\"RUNNING\"}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("status").asText()).isEqualTo("RUNNING");

    AggregatedHttpResponse succeedResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-002\",\"status\":\"SUCCEEDED\","
                + "\"commit_metadata\":{\"rows_written\":100}}");

    assertSuccess(succeedResponse);
    assertThat(json(succeedResponse).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(json(succeedResponse).path("commit_metadata").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-SYNC-004-03: syncTransaction rejects missing transaction_key")
  void syncTransactionRejectsMissingTransactionKey() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"status\":\"RUNNING\"}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("transaction_key is required");
  }

  @Test
  @DisplayName("P3-SYNC-004-04: syncTransaction rejects missing status")
  void syncTransactionRejectsMissingStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-003\"}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("status is required");
  }

  @Test
  @DisplayName("P3-SYNC-004-05: syncTransaction rejects invalid status")
  void syncTransactionRejectsInvalidStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-004\",\"status\":\"INVALID_STATUS\"}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText())
        .contains("Invalid Lance transaction status");
  }

  @Test
  @DisplayName("P3-SYNC-004-06: syncTransaction rejects declared-only table")
  void syncTransactionRejectsDeclaredOnlyTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-005\",\"status\":\"QUEUED\"}");

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("message").asText()).contains("must be materialized");
  }

  // ========== 状态机边界值测试 ==========

  @Test
  @DisplayName("P3-TRANS-STATUS-01: syncTransaction accepts QUEUED status")
  void syncTransactionAcceptsQueuedStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-queued\",\"status\":\"QUEUED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("QUEUED");
  }

  @Test
  @DisplayName("P3-TRANS-STATUS-02: syncTransaction accepts RUNNING status")
  void syncTransactionAcceptsRunningStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-running\",\"status\":\"RUNNING\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("P3-TRANS-STATUS-03: syncTransaction accepts SUCCEEDED status")
  void syncTransactionAcceptsSucceededStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-succeeded\",\"status\":\"SUCCEEDED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("P3-TRANS-STATUS-04: syncTransaction accepts FAILED status")
  void syncTransactionAcceptsFailedStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-failed\",\"status\":\"FAILED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("P3-TRANS-STATUS-05: syncTransaction accepts CANCELED status")
  void syncTransactionAcceptsCanceledStatus() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-canceled\",\"status\":\"CANCELED\"}");

    assertSuccess(response);
    assertThat(json(response).path("status").asText()).isEqualTo("CANCELED");
  }

  // ========== P3-TRANS: Transaction 查询测试 ==========

  @Test
  @DisplayName("P3-TRANS-001: transaction list returns synced transactions")
  void transactionListReturnsSyncedTransactions() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-list-1\",\"status\":\"SUCCEEDED\"}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-list-2\",\"status\":\"FAILED\"}"));

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/list", "{}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("transactions").isArray()).isTrue();
    assertThat(list.path("transactions").size()).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("P3-TRANS-002: transaction list returns empty for table without transactions")
  void transactionListReturnsEmptyForNoTransactions() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/list", "{}");

    assertSuccess(listResponse);
    assertThat(json(listResponse).path("transactions").isArray()).isTrue();
    assertThat(json(listResponse).path("transactions")).isEmpty();
  }

  @Test
  @DisplayName("P3-TRANS-003: transaction list filters by status")
  void transactionListFiltersByStatus() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-filter-s\",\"status\":\"SUCCEEDED\"}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-filter-f\",\"status\":\"FAILED\"}"));

    AggregatedHttpResponse listResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/list", "{\"status\":\"SUCCEEDED\"}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("transactions").isArray()).isTrue();
    assertThat(list.path("transactions").size()).isEqualTo(1);
    assertThat(list.path("transactions").get(0).path("transaction_key").asText())
        .isEqualTo("tx-filter-s");
  }

  @Test
  @DisplayName("P3-TRANS-004: describeTransaction returns transaction details")
  void describeTransactionReturnsTransactionDetails() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-detail\",\"status\":\"SUCCEEDED\","
                + "\"actions\":[{\"type\":\"insert\",\"rows\":500}],"
                + "\"commit_metadata\":{\"version\":10,\"rows_written\":500}}"));

    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/describe",
            "{\"transaction_key\":\"tx-detail\"}");

    assertSuccess(describeResponse);
    JsonNode tx = json(describeResponse);
    assertThat(tx.path("transaction_key").asText()).isEqualTo("tx-detail");
    assertThat(tx.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(tx.path("actions").isArray()).isTrue();
    assertThat(tx.path("commit_metadata").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-TRANS-005: describeTransaction returns 404 for non-existent transaction")
  void describeTransactionReturnsNotFoundForMissingTransaction() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/describe",
            "{\"transaction_key\":\"missing-tx\"}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("not found");
  }

  @Test
  @DisplayName("P3-TRANS-006: describeTransaction accepts key alias")
  void describeTransactionAcceptsKeyAlias() throws Exception {
    createActiveTableFixture();

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/metadata/sync/transaction",
            "{\"transaction_key\":\"tx-alias\",\"status\":\"QUEUED\"}"));

    AggregatedHttpResponse describeResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/transaction/describe", "{\"key\":\"tx-alias\"}");

    assertSuccess(describeResponse);
    assertThat(json(describeResponse).path("transaction_key").asText()).isEqualTo("tx-alias");
  }
}
