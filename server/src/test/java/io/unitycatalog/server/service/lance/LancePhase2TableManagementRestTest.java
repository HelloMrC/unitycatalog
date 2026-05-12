package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.utils.ServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2TableManagementRestTest extends BaseLancePhase2RestTest {
  private final LanceIdentifierCodec identifierCodec = new LanceIdentifierCodec();
  private LanceTableRepository tableRepository;

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
  @DisplayName("P2-META-011 rename_table updates metadata only")
  void renameTableUpdatesMetadataOnly() throws Exception {
    createActiveTableFixture();

    // Get original state
    LanceAssetDAO originalAsset = asset(P2_ACTIVE_TABLE_ID);
    LanceTableDAO originalTable =
        tableRepository.findTableByAssetId(originalAsset.getId()).orElseThrow();
    String originalStorageLocation = originalTable.getStorageLocation();

    // Rename table
    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/rename",
            "{\"new_table_name\":\"renamed_embeddings\"}");

    assertSuccess(response);
    assertThat(json(response).path("id").asText()).contains("renamed_embeddings");
    assertThat(json(response).path("state").asText()).isEqualTo("ACTIVE");

    // Verify metadata changed
    LanceAssetDAO renamedAsset = asset("prod$team_a$renamed_embeddings");
    assertThat(renamedAsset.getName()).isEqualTo("renamed_embeddings");
    assertThat(renamedAsset.getPathKey()).isEqualTo("prod/team_a/renamed_embeddings");

    // Verify storage_location unchanged
    LanceTableDAO renamedTable =
        tableRepository.findTableByAssetId(renamedAsset.getId()).orElseThrow();
    assertThat(renamedTable.getStorageLocation()).isEqualTo(originalStorageLocation);

    // Original path_key should no longer find the table
    assertThat(tableRepository.findAssetByPathKey("prod/team_a/embeddings")).isEmpty();
  }

  @Test
  @DisplayName("P2-META-012 rename_table to different namespace")
  void renameTableToDifferentNamespace() throws Exception {
    createActiveTableFixture();

    // Create new namespace
    AggregatedHttpResponse nsResponse = postJson("/v1/namespace/prod$team_b/create", "{}");
    assertSuccess(nsResponse);

    // Rename to new namespace
    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/rename",
            "{\"new_table_name\":\"moved_embeddings\",\"new_namespace\":[\"prod\",\"team_b\"]}");

    assertSuccess(response);
    assertThat(json(response).path("id").asText()).contains("team_b");
    assertThat(json(response).path("id").asText()).contains("moved_embeddings");

    // Verify new path_key
    LanceAssetDAO movedAsset = asset("prod$team_b$moved_embeddings");
    assertThat(movedAsset.getPathKey()).isEqualTo("prod/team_b/moved_embeddings");
  }

  @Test
  @DisplayName("P2-META-013 rename_table requires new_table_name")
  void renameTableRequiresNewTableName() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response = postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/rename", "{}");

    assertThat(response.status().code()).isEqualTo(400);
    assertThat(response.contentUtf8()).contains("new_table_name", "required");
  }

  @Test
  @DisplayName("P2-META-014 rename_table conflict if target exists")
  void renameTableConflictIfExists() throws Exception {
    createActiveTableFixture();
    createDeclaredTableFixture();

    // Try to rename active table to existing declared table name
    // P2_DECLARED_TABLE_ID is "prod$team_a$declared_only", so the table name is "declared_only"
    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/rename",
            "{\"new_table_name\":\"declared_only\"}");

    assertThat(response.status().code()).isEqualTo(409);
    assertThat(response.contentUtf8()).containsIgnoringCase("already exists");
  }

  @Test
  @DisplayName("P2-META-015 restore_table returns 501 UNIMPLEMENTED")
  void restoreTableReturnsUnimplemented() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/restore", "{\"version\":1}");

    assertThat(response.status().code()).isEqualTo(501);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("unimplemented");
    assertThat(response.contentUtf8()).contains("Lance file format");
  }

  private LanceAssetDAO asset(String identifier) {
    return tableRepository.findAssetByPathKey(tablePathKey(identifier)).orElseThrow();
  }

  private String tablePathKey(String identifier) {
    return identifierCodec.toPathKey(identifierCodec.decodeIdentifier(identifier, null));
  }
}
