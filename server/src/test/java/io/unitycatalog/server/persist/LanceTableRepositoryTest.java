package io.unitycatalog.server.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.dao.LanceTableDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanceTableRepositoryTest {
  private LanceNamespaceRepository namespaceRepository;
  private LanceTableRepository tableRepository;
  private UUID rootScopeId;
  private UUID namespaceId;

  @BeforeEach
  void setUp() {
    Properties properties = new Properties();
    properties.setProperty(Property.SERVER_ENV.getKey(), "test");

    ServerProperties serverProperties = new ServerProperties(properties);
    HibernateConfigurator hibernateConfigurator = new HibernateConfigurator(serverProperties);
    Repositories repositories =
        new Repositories(hibernateConfigurator.getSessionFactory(), serverProperties);

    namespaceRepository = repositories.getLanceNamespaceRepository();
    tableRepository = repositories.getLanceTableRepository();
    rootScopeId = UUID.randomUUID();

    LanceNamespaceDAO namespace =
        namespaceRepository.createNamespace(
            rootScopeId, null, "prod", 1, "prod", "prod", "phase1-owner", Map.of());
    namespaceId = namespace.getId();
  }

  @Test
  void registerTableCreatesAssetAndTableRows() {
    LanceAssetDAO asset =
        tableRepository.registerTable(
            namespaceId,
            "embeddings",
            "prod/embeddings",
            "prod$embeddings",
            "file:///tmp/uc-lance/embeddings.lance",
            "{\"fields\":[]}",
            "{\"provider\":\"file\"}",
            Map.of("table_type", "lance"),
            "phase1-owner");

    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(asset.getAssetType()).isEqualTo("TABLE");
    assertThat(asset.getState()).isEqualTo("ACTIVE");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(tableRepository.getTableProperties(asset.getId()))
        .containsEntry("table_type", "lance");
  }

  @Test
  void listTablesExcludesDeclaredOnlyUnlessRequested() {
    tableRepository.registerTable(
        namespaceId,
        "active_table",
        "prod/active_table",
        "prod$active_table",
        "file:///tmp/uc-lance/active.lance",
        null,
        null,
        Map.of(),
        "phase1-owner");
    tableRepository.declareTable(
        namespaceId,
        "declared_table",
        "prod/declared_table",
        "prod$declared_table",
        "file:///tmp/uc-lance/declared.lance",
        null,
        null,
        Map.of(),
        "phase1-owner");

    List<LanceAssetDAO> withoutDeclared = tableRepository.listTables(namespaceId, false);
    List<LanceAssetDAO> withDeclared = tableRepository.listTables(namespaceId, true);

    assertThat(withoutDeclared).extracting(LanceAssetDAO::getName).containsExactly("active_table");
    assertThat(withDeclared)
        .extracting(LanceAssetDAO::getName)
        .containsExactly("active_table", "declared_table");
  }

  @Test
  void registerTableRejectsDuplicateCanonicalPath() {
    tableRepository.registerTable(
        namespaceId,
        "embeddings",
        "prod/embeddings",
        "prod$embeddings",
        "file:///tmp/uc-lance/embeddings.lance",
        null,
        null,
        Map.of(),
        "phase1-owner");

    BaseException exception =
        assertThrows(
            BaseException.class,
            () ->
                tableRepository.registerTable(
                    namespaceId,
                    "embeddings",
                    "prod/embeddings",
                    "prod$embeddings",
                    "file:///tmp/uc-lance/embeddings.lance",
                    null,
                    null,
                    Map.of(),
                    "phase1-owner"));

    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
  }

  @Test
  void markTableMaterializedActivatesDeclaredTableAndUpdatesMetadata() {
    LanceAssetDAO asset =
        tableRepository.declareTable(
            namespaceId,
            "declared",
            "prod/declared",
            "prod$declared",
            "file:///tmp/uc-lance/declared.lance",
            null,
            null,
            Map.of(),
            "phase1-owner");

    tableRepository.markTableMaterialized(
        asset.getId(),
        "file:///tmp/uc-lance/materialized.lance",
        "file:///tmp/uc-lance/materialized.lance",
        "{\"fields\":[{\"name\":\"id\"}]}",
        7L,
        "{\"numRows\":10}",
        "phase2-writer");

    LanceAssetDAO materialized = tableRepository.findAssetById(asset.getId()).orElseThrow();
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(materialized.getState()).isEqualTo("ACTIVE");
    assertThat(materialized.getUpdatedBy()).isEqualTo("phase2-writer");
    assertThat(table.getIsOnlyDeclared()).isFalse();
    assertThat(table.getStorageLocation()).isEqualTo("file:///tmp/uc-lance/materialized.lance");
    assertThat(table.getCurrentVersion()).isEqualTo(7L);
    assertThat(table.getArrowSchemaJson()).contains("\"name\":\"id\"");
    assertThat(table.getStatsJson()).isEqualTo("{\"numRows\":10}");
  }

  @Test
  void updateTableExecutionMetadataDoesNotOverwriteSchemaWithNull() {
    LanceAssetDAO asset =
        tableRepository.registerTable(
            namespaceId,
            "active_metadata",
            "prod/active_metadata",
            "prod$active_metadata",
            "file:///tmp/uc-lance/active-metadata.lance",
            "{\"fields\":[{\"name\":\"existing\"}]}",
            null,
            Map.of(),
            "phase1-owner");

    tableRepository.updateTableExecutionMetadata(
        asset.getId(), 9L, null, "{\"numRows\":20}", "phase2-writer");

    LanceAssetDAO updated = tableRepository.findAssetById(asset.getId()).orElseThrow();
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(updated.getUpdatedBy()).isEqualTo("phase2-writer");
    assertThat(table.getCurrentVersion()).isEqualTo(9L);
    assertThat(table.getArrowSchemaJson()).contains("\"name\":\"existing\"");
    assertThat(table.getStatsJson()).isEqualTo("{\"numRows\":20}");
  }

  @Test
  void updateTableStatsRefreshesStatsCacheOnly() {
    LanceAssetDAO asset =
        tableRepository.registerTable(
            namespaceId,
            "stats_cache",
            "prod/stats_cache",
            "prod$stats_cache",
            "file:///tmp/uc-lance/stats-cache.lance",
            "{\"fields\":[]}",
            null,
            Map.of(),
            "phase1-owner");

    tableRepository.updateTableStats(asset.getId(), "{\"numRows\":30}", "phase2-reader");

    LanceAssetDAO updated = tableRepository.findAssetById(asset.getId()).orElseThrow();
    LanceTableDAO table = tableRepository.findTableByAssetId(asset.getId()).orElseThrow();
    assertThat(updated.getUpdatedBy()).isEqualTo("phase2-reader");
    assertThat(table.getStatsJson()).isEqualTo("{\"numRows\":30}");
    assertThat(table.getCurrentVersion()).isNull();
    assertThat(table.getArrowSchemaJson()).isEqualTo("{\"fields\":[]}");
  }
}
