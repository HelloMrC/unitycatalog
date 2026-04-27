package io.unitycatalog.server.auth.decorator;

import static io.unitycatalog.server.model.SecurableType.LANCE_NAMESPACE;
import static io.unitycatalog.server.model.SecurableType.LANCE_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.persist.LanceNamespaceRepository;
import io.unitycatalog.server.persist.LanceTableRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceAssetDAO;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanceResourceKeyMapperTest {
  private Repositories repositories;
  private LanceNamespaceRepository namespaceRepository;
  private LanceTableRepository tableRepository;
  private UUID rootScopeId;

  @BeforeEach
  void setUp() {
    Properties properties = new Properties();
    properties.setProperty(Property.SERVER_ENV.getKey(), "test");

    ServerProperties serverProperties = new ServerProperties(properties);
    HibernateConfigurator hibernateConfigurator = new HibernateConfigurator(serverProperties);
    repositories = new Repositories(hibernateConfigurator.getSessionFactory(), serverProperties);
    repositories.getMetastoreRepository().initMetastoreIfNeeded();

    namespaceRepository = repositories.getLanceNamespaceRepository();
    tableRepository = repositories.getLanceTableRepository();
    rootScopeId = repositories.getMetastoreRepository().getMetastoreId();
  }

  @Test
  void keyMapperDelegatesLanceNamespaceAndTableResolution() {
    LanceNamespaceDAO root =
        namespaceRepository.createNamespace(
            rootScopeId, null, "prod", 1, "prod", "prod", "phase1-owner", Map.of());
    LanceNamespaceDAO child =
        namespaceRepository.createNamespace(
            rootScopeId,
            root.getId(),
            "team_a",
            2,
            "prod/team_a",
            "team_a",
            "phase1-owner",
            Map.of());
    LanceAssetDAO table =
        tableRepository.registerTable(
            child.getId(),
            "embeddings",
            "prod/team_a/embeddings",
            "prod$team_a$embeddings",
            "file:///tmp/uc-lance/embeddings.lance",
            null,
            null,
            Map.of(),
            "phase1-owner");

    Map<SecurableType, Object> namespaceIds =
        repositories.getKeyMapper().mapResourceKeys(Map.of(LANCE_NAMESPACE, "prod$team_a"));
    assertThat(namespaceIds).containsEntry(LANCE_NAMESPACE, child.getId());

    Map<SecurableType, Object> tableIds =
        repositories.getKeyMapper().mapResourceKeys(Map.of(LANCE_TABLE, "prod$team_a$embeddings"));
    assertThat(tableIds).containsEntry(LANCE_TABLE, table.getId());
    assertThat(tableIds).containsEntry(LANCE_NAMESPACE, child.getId());
  }
}
