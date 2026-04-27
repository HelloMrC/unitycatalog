package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.persist.LanceApiKeyRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1MetadataPersistenceTest extends BaseLancePhase1RestTest {

  @Test
  @DisplayName("P1-META-001/P1-META-002 Phase 1 DDL creates only required Lance metadata tables")
  void phase1DdlCreatesRequiredMetadataTables() {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      List<?> tables =
          session
              .createNativeQuery(
                  "select table_name from information_schema.tables where table_name like 'UC_LANCE_%'")
              .getResultList();

      assertThat(tables.toString())
          .contains(
              "UC_LANCE_NAMESPACES", "UC_LANCE_ASSETS", "UC_LANCE_TABLES", "UC_LANCE_API_KEYS");
      assertThat(tables.toString())
          .doesNotContain(
              "UC_LANCE_INDICES", "UC_LANCE_VERSIONS", "UC_LANCE_TAGS", "UC_LANCE_TRANSACTIONS");
    }
  }

  @Test
  @DisplayName("P1-META-003 namespace unique key prevents duplicate parent/name combinations")
  void namespaceUniqueKeyPreventsDuplicateParentNameCombinations() throws Exception {
    assertSuccess(postJson("/v1/namespace/prod/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_a/create", createNamespaceRequest()));

    // Attempt to create duplicate namespace with same parent and name
    AggregatedHttpResponse duplicate =
        postJson("/v1/namespace/prod$team_a/create", createNamespaceRequest());
    assertLanceErrorShape(duplicate, 409);
    assertThat(json(duplicate).path("type").asText()).containsIgnoringCase("already");

    // Verify only one namespace exists in database
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Number count =
          (Number)
              session
                  .createNativeQuery(
                      "select count(*) from uc_lance_namespaces where path_key = 'prod/team_a'")
                  .getSingleResult();
      assertThat(count.intValue()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("P1-META-004/P1-META-008 path_key and root_scope_id persist canonical metadata")
  void pathKeyAndRootScopeIdPersistCanonicalMetadata() {
    createRootAndChildNamespaces();

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object[] row =
          (Object[])
              session
                  .createNativeQuery(
                      "select path_key, root_scope_id from uc_lance_namespaces where path_key = :pathKey")
                  .setParameter("pathKey", "prod/team_a")
                  .getSingleResult();

      assertThat(row[0]).isEqualTo("prod/team_a");
      assertThat(row[0].toString()).doesNotContain("$");
      assertThat(row[1]).isNotNull();
    }
  }

  @Test
  @DisplayName("P1-META-005 table asset and table rows stay consistent")
  void tableAssetAndTableRowsStayConsistent() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object[] row =
          (Object[])
              session
                  .createNativeQuery(
                      "select a.path_key, a.asset_type, a.state, t.asset_id "
                          + "from uc_lance_assets a join uc_lance_tables t on a.id = t.asset_id "
                          + "where a.path_key = :pathKey")
                  .setParameter("pathKey", "prod/team_a/embeddings")
                  .getSingleResult();

      assertThat(row).contains("prod/team_a/embeddings", "TABLE", "ACTIVE");
    }
  }

  @Test
  @DisplayName("P1-META-006/P1-META-007 storage template persists only non-sensitive fields")
  void storageTemplatePersistsOnlyNonSensitiveFields() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + TABLE_ID + "/register",
            "{"
                + "\"location\":\""
                + TABLE_LOCATION
                + "\","
                + "\"storage_options_template\":{\"provider\":\"s3\",\"region\":\"us-west-2\"},"
                + "\"properties\":{\"table_type\":\"lance\"}"
                + "}"));
    assertSuccess(postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":true}"));

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object template =
          session
              .createNativeQuery(
                  "select storage_options_template_json from uc_lance_tables where asset_id in "
                      + "(select id from uc_lance_assets where path_key = :pathKey)")
              .setParameter("pathKey", "prod/team_a/embeddings")
              .getSingleResult();

      assertThat(template.toString()).contains("provider", "region");
      assertThat(template.toString())
          .doesNotContain("token", "session", "secret", "expires", "access_key");
    }
  }

  @Test
  @DisplayName("P1-META-010 table status transitions follow declared and deregister semantics")
  void tableStatusTransitionsFollowDeclaredAndDeregisterSemantics() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));
    assertAssetState("prod/team_a/declared_only", "DECLARED");

    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
    assertAssetState("prod/team_a/embeddings", "ACTIVE");

    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/deregister", "{\"delete_physical_data\":false}"));
    assertAssetState("prod/team_a/embeddings", "DEREGISTERED");
  }

  private void assertAssetState(String pathKey, String expectedState) {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object state =
          session
              .createNativeQuery("select state from uc_lance_assets where path_key = :pathKey")
              .setParameter("pathKey", pathKey)
              .getSingleResult();

      assertThat(state.toString()).isEqualTo(expectedState);
    }
  }

  @Test
  @DisplayName("P1-META-009 concurrent same-parent namespace create enforces unique constraint")
  void concurrentSameParentNamespaceCreateEnforcesUniqueConstraint() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));

    int workers = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    for (int i = 0; i < workers; i++) {
      executor.submit(
          () -> {
            start.await();
            postJson("/v1/namespace/" + CHILD_NAMESPACE + "/create", createNamespaceRequest());
            return null;
          });
    }

    start.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Number count =
          (Number)
              session
                  .createNativeQuery(
                      "select count(*) from uc_lance_namespaces "
                          + "where root_scope_id is not null and path_key = :pathKey")
                  .setParameter("pathKey", "prod/team_a")
                  .getSingleResult();

      assertThat(count.intValue()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("P1-AUTH-006 API key table stores only key hash")
  void apiKeyTableStoresOnlyKeyHash() {
    Repositories repositories =
        new Repositories(
            hibernateConfigurator.getSessionFactory(), new ServerProperties(serverProperties));
    repositories
        .getLanceApiKeyRepository()
        .createApiKey(
            "phase1-valid-api-key",
            "phase1-service-principal",
            "SERVICE_PRINCIPAL",
            LanceApiKeyRepository.ACTIVE_STATUS,
            "phase1-test",
            null,
            null);

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object[] row =
          (Object[])
              session
                  .createNativeQuery(
                      "select key_hash, principal_id, principal_type, expires_at, revoked_at "
                          + "from uc_lance_api_keys where principal_id = :principalId")
                  .setParameter("principalId", "phase1-service-principal")
                  .getSingleResult();

      assertThat(row).contains("SERVICE_PRINCIPAL");
      assertThat(Arrays.toString(row)).doesNotContain("phase1-valid-api-key");
    }
  }
}
