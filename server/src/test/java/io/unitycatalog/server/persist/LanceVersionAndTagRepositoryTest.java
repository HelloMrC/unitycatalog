package io.unitycatalog.server.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.dao.LanceTagDAO;
import io.unitycatalog.server.persist.dao.LanceVersionDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanceVersionAndTagRepositoryTest {
  private LanceVersionRepository versionRepository;
  private LanceTagRepository tagRepository;
  private LanceTableRepository tableRepository;
  private UUID tableAssetId;

  @BeforeEach
  void setUp() {
    Properties properties = new Properties();
    properties.setProperty(Property.SERVER_ENV.getKey(), "test");

    ServerProperties serverProperties = new ServerProperties(properties);
    HibernateConfigurator hibernateConfigurator = new HibernateConfigurator(serverProperties);
    Repositories repositories =
        new Repositories(hibernateConfigurator.getSessionFactory(), serverProperties);

    LanceNamespaceRepository namespaceRepository = repositories.getLanceNamespaceRepository();
    tableRepository = repositories.getLanceTableRepository();
    versionRepository = repositories.getLanceVersionRepository();
    tagRepository = repositories.getLanceTagRepository();

    LanceNamespaceDAO namespace =
        namespaceRepository.createNamespace(
            UUID.randomUUID(), null, "prod", 1, "prod", "prod", "phase3-owner", Map.of());
    LanceAssetDAO table =
        tableRepository.registerTable(
            namespace.getId(),
            "embeddings",
            "prod/embeddings",
            "prod$embeddings",
            "file:///tmp/uc-lance/embeddings.lance",
            "{\"fields\":[]}",
            null,
            Map.of(),
            "phase3-owner");
    tableAssetId = table.getId();
  }

  @Test
  void upsertVersionCreatesAndUpdatesVersionMetadata() {
    Date timestamp = new Date();
    LanceVersionDAO created =
        versionRepository.upsertVersion(
            tableAssetId,
            5L,
            "insert",
            timestamp,
            "file:///tmp/uc-lance/_versions/5.manifest",
            1024L,
            "etag-5",
            "{\"source\":\"worker\"}",
            "{\"numRows\":10}",
            "phase3-writer");

    assertThat(created.getVersion()).isEqualTo(5L);
    assertThat(created.getOperation()).isEqualTo("insert");
    assertThat(created.getManifestPath()).contains("5.manifest");

    LanceVersionDAO updated =
        versionRepository.upsertVersion(
            tableAssetId,
            5L,
            "reconcile",
            timestamp,
            "file:///tmp/uc-lance/_versions/5-reconciled.manifest",
            2048L,
            "etag-5b",
            "{\"source\":\"reconcile\"}",
            "{\"numRows\":11}",
            "phase3-admin");

    assertThat(updated.getId()).isEqualTo(created.getId());
    assertThat(updated.getOperation()).isEqualTo("reconcile");
    assertThat(updated.getManifestSize()).isEqualTo(2048L);
    assertThat(updated.getCreatedBy()).isEqualTo("phase3-admin");
  }

  @Test
  void listVersionsOrdersByDescendingVersionAndSupportsFilters() {
    versionRepository.upsertVersion(
        tableAssetId, 1L, "insert", new Date(), null, null, null, null, null, "writer");
    versionRepository.upsertVersion(
        tableAssetId, 2L, "update", new Date(), null, null, null, null, null, "writer");
    versionRepository.upsertVersion(
        tableAssetId, 3L, "delete", new Date(), null, null, null, null, null, "writer");

    List<LanceVersionDAO> all =
        versionRepository.listVersions(
            tableAssetId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    assertThat(all).extracting(LanceVersionDAO::getVersion).containsExactly(3L, 2L, 1L);

    List<LanceVersionDAO> filtered =
        versionRepository.listVersions(
            tableAssetId, Optional.of(2L), Optional.of(3L), Optional.of(1), Optional.empty());
    assertThat(filtered).extracting(LanceVersionDAO::getVersion).containsExactly(3L, 2L);
  }

  @Test
  void tagRepositoryCreatesUpdatesListsAndDeletesTags() {
    LanceTagDAO created =
        tagRepository.createTag(
            tableAssetId, "release-1.0", 5L, "{\"description\":\"first\"}", "phase3-owner");

    assertThat(created.getTableAssetId()).isEqualTo(tableAssetId);
    assertThat(created.getVersion()).isEqualTo(5L);
    assertThat(tagRepository.findTag(tableAssetId, "release-1.0")).isPresent();

    LanceTagDAO updated =
        tagRepository.updateTag(
            tableAssetId, "release-1.0", 6L, "{\"description\":\"updated\"}", "phase3-admin");
    assertThat(updated.getVersion()).isEqualTo(6L);
    assertThat(updated.getMetadataJson()).contains("updated");
    assertThat(updated.getUpdatedBy()).isEqualTo("phase3-admin");

    tagRepository.createTag(tableAssetId, "dev", 7L, null, "phase3-owner");
    List<LanceTagDAO> tags =
        tagRepository.listTags(tableAssetId, Optional.empty(), Optional.empty());
    assertThat(tags).extracting(LanceTagDAO::getTagName).containsExactly("dev", "release-1.0");

    tagRepository.deleteTag(tableAssetId, "release-1.0");
    assertThat(tagRepository.findTag(tableAssetId, "release-1.0")).isEmpty();
  }

  @Test
  void createTagRejectsDuplicateTagNameForTable() {
    tagRepository.createTag(tableAssetId, "release-1.0", 5L, null, "phase3-owner");

    BaseException exception =
        assertThrows(
            BaseException.class,
            () -> tagRepository.createTag(tableAssetId, "release-1.0", 6L, null, "phase3-owner"));

    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
  }
}
