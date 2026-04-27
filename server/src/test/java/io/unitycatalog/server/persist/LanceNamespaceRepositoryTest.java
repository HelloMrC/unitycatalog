package io.unitycatalog.server.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceNamespaceDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanceNamespaceRepositoryTest {
  private LanceNamespaceRepository repository;
  private UUID rootScopeId;

  @BeforeEach
  void setUp() {
    Properties properties = new Properties();
    properties.setProperty(Property.SERVER_ENV.getKey(), "test");

    ServerProperties serverProperties = new ServerProperties(properties);
    HibernateConfigurator hibernateConfigurator = new HibernateConfigurator(serverProperties);
    Repositories repositories =
        new Repositories(hibernateConfigurator.getSessionFactory(), serverProperties);

    repository = repositories.getLanceNamespaceRepository();
    rootScopeId = UUID.randomUUID();
  }

  @Test
  void createNamespacePersistsCanonicalFieldsAndProperties() {
    LanceNamespaceDAO root =
        repository.createNamespace(
            rootScopeId,
            null,
            "prod",
            1,
            "prod",
            "prod",
            "phase1-owner",
            Map.of("purpose", "phase1-test"));

    assertThat(root.getPathKey()).isEqualTo("prod");
    assertThat(root.getDepth()).isEqualTo(1);
    assertThat(root.getParentNamespaceId()).isNull();
    assertThat(repository.getNamespaceProperties(root.getId()))
        .containsEntry("purpose", "phase1-test");
  }

  @Test
  void listChildNamespacesReturnsOnlyDirectChildrenInNameOrder() {
    LanceNamespaceDAO root =
        repository.createNamespace(
            rootScopeId, null, "prod", 1, "prod", "prod", "phase1-owner", Map.of());

    repository.createNamespace(
        rootScopeId, root.getId(), "team_b", 2, "prod/team_b", "team_b", "phase1-owner", Map.of());
    repository.createNamespace(
        rootScopeId, root.getId(), "team_a", 2, "prod/team_a", "team_a", "phase1-owner", Map.of());
    repository.createNamespace(rootScopeId, null, "dev", 1, "dev", "dev", "phase1-owner", Map.of());

    List<LanceNamespaceDAO> children = repository.listChildNamespaces(rootScopeId, root.getId());
    assertThat(children).extracting(LanceNamespaceDAO::getName).containsExactly("team_a", "team_b");
  }

  @Test
  void createNamespaceRejectsDuplicateCanonicalPath() {
    repository.createNamespace(
        rootScopeId, null, "prod", 1, "prod", "prod", "phase1-owner", Map.of());

    BaseException exception =
        assertThrows(
            BaseException.class,
            () ->
                repository.createNamespace(
                    rootScopeId, null, "prod", 1, "prod", "prod", "phase1-owner", Map.of()));

    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
  }
}
