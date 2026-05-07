package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2ReconcileRestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceTableRepository tableRepository;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    tableRepository = repositories.getLanceTableRepository();
  }

  @Test
  @DisplayName("P2-META-009 reconcile dry-run can plan backend committed repairs")
  void reconcileDryRunCanPlanBackendCommittedRepairs() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/admin/reconcile", "{\"table_id\":\"" + P2_ACTIVE_TABLE_ID + "\",\"dry_run\":true}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("status").asText()).isEqualTo("DRY_RUN");
    assertThat(body.path("backend_committed").asBoolean()).isTrue();
    assertThat(body.path("plan").toString())
        .contains("current_version", "arrow_schema_json", "stats_json");
    assertThat(table(P2_ACTIVE_TABLE_ID).getCurrentVersion()).isNull();
  }

  @Test
  @DisplayName("P2-META-010 controlled reconcile backfills UC metadata")
  void controlledReconcileBackfillsUcMetadata() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/admin/reconcile",
            "{"
                + "\"table_id\":\""
                + P2_ACTIVE_TABLE_ID
                + "\","
                + "\"dry_run\":false,"
                + "\"current_version\":5,"
                + "\"arrow_schema_json\":\"{\\\"schema\\\":\\\"reconciled\\\"}\","
                + "\"stats_json\":\"{\\\"numRows\\\":5}\""
                + "}");

    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("status").asText()).isEqualTo("UPDATED");
    assertThat(body.path("audit").toString()).contains("operator", "before", "after");
    LanceTableDAO table = table(P2_ACTIVE_TABLE_ID);
    assertThat(table.getCurrentVersion()).isEqualTo(5L);
    assertThat(table.getArrowSchemaJson()).contains("reconciled");
    assertThat(table.getStatsJson()).contains("numRows");
  }

  private LanceTableDAO table(String identifier) {
    LanceAssetDAO asset =
        tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
    return tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
