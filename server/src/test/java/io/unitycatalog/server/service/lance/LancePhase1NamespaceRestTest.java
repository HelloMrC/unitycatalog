package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import java.util.Map;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1NamespaceRestTest extends BaseLancePhase1RestTest {

  @Test
  @DisplayName("P1-NS-001/P1-NS-006/P1-NS-007 create, describe, and exists namespace")
  void createDescribeAndExistsNamespace() throws Exception {
    AggregatedHttpResponse create =
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest());
    assertSuccess(create);

    AggregatedHttpResponse describe =
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/describe", "{}");
    assertSuccess(describe);
    JsonNode described = json(describe);
    assertThat(described.path("id").asText()).isEqualTo(ROOT_NAMESPACE);
    assertThat(described.path("properties").path("purpose").asText()).isEqualTo("phase1-test");

    AggregatedHttpResponse exists = postJson("/v1/namespace/" + ROOT_NAMESPACE + "/exists", "{}");
    assertSuccess(exists);
    assertThat(json(exists).path("exists").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P1-NS-002/P1-NS-004/P1-NS-005 create deep namespace and list direct children")
  void createDeepNamespaceAndListDirectChildren() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/namespace/prod$team_a$feature_store/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_b/create", createNamespaceRequest()));

    AggregatedHttpResponse rootList = getLance("/v1/namespace/prod/list");
    assertSuccess(rootList);
    JsonNode rootChildren = json(rootList).path("namespaces");
    assertThat(rootChildren.toString()).contains("prod$team_a", "prod$team_b");
    assertThat(rootChildren.toString()).doesNotContain("feature_store");

    AggregatedHttpResponse childList = getLance("/v1/namespace/prod$team_a/list");
    assertSuccess(childList);
    assertThat(json(childList).path("namespaces").toString()).contains("feature_store");
  }

  @Test
  @DisplayName("P1-ID-001 default $ delimiter creates namespace with correct path segments")
  void defaultDollarDelimiterCreatesNamespaceWithCorrectPathSegments() throws Exception {
    assertSuccess(postJson("/v1/namespace/prod/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_a/create", createNamespaceRequest()));

    // Verify internal path_key uses canonical form (slash-separated, not dollar)
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object rootPathKey =
          session
              .createNativeQuery("select path_key from uc_lance_namespaces where name = 'prod'")
              .getSingleResult();
      assertThat(rootPathKey.toString()).isEqualTo("prod");

      Object childPathKey =
          session
              .createNativeQuery("select path_key from uc_lance_namespaces where name = 'team_a'")
              .getSingleResult();
      assertThat(childPathKey.toString()).isEqualTo("prod/team_a");
    }

    // Describe returns identifier with default dollar delimiter
    AggregatedHttpResponse describe = postJson("/v1/namespace/prod$team_a/describe", "{}");
    assertSuccess(describe);
    assertThat(json(describe).path("id").asText()).isEqualTo("prod$team_a");
  }

  @Test
  @DisplayName("P1-ID-003 deep 4-layer namespace has correct depth and parent-child relations")
  void deepFourLayerNamespaceHasCorrectDepthAndParentChildRelations() throws Exception {
    // Create 4-layer namespace: prod/team_a/ml/embeddings
    assertSuccess(postJson("/v1/namespace/prod/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_a/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_a$ml/create", createNamespaceRequest()));
    assertSuccess(
        postJson("/v1/namespace/prod$team_a$ml$embeddings/create", createNamespaceRequest()));

    // Verify depth and path_key in database
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      // Verify embeddings namespace
      Object[] embeddingsRow =
          (Object[])
              session
                  .createNativeQuery(
                      "select depth, path_key from uc_lance_namespaces "
                          + "where path_key = 'prod/team_a/ml/embeddings'")
                  .getSingleResult();
      assertThat(embeddingsRow[0]).isEqualTo(4);
      assertThat(embeddingsRow[1].toString()).isEqualTo("prod/team_a/ml/embeddings");

      // Verify ml namespace
      Object[] mlRow =
          (Object[])
              session
                  .createNativeQuery(
                      "select depth, path_key from uc_lance_namespaces "
                          + "where path_key = 'prod/team_a/ml'")
                  .getSingleResult();
      assertThat(mlRow[0]).isEqualTo(3);
      assertThat(mlRow[1].toString()).isEqualTo("prod/team_a/ml");

      // Verify team_a namespace
      Object[] teamARow =
          (Object[])
              session
                  .createNativeQuery(
                      "select depth, path_key from uc_lance_namespaces "
                          + "where path_key = 'prod/team_a'")
                  .getSingleResult();
      assertThat(teamARow[0]).isEqualTo(2);
      assertThat(teamARow[1].toString()).isEqualTo("prod/team_a");

      // Verify prod namespace
      Object[] prodRow =
          (Object[])
              session
                  .createNativeQuery(
                      "select depth, path_key from uc_lance_namespaces "
                          + "where path_key = 'prod'")
                  .getSingleResult();
      assertThat(prodRow[0]).isEqualTo(1);
      assertThat(prodRow[1].toString()).isEqualTo("prod");
    }

    // List returns only direct children at each level
    AggregatedHttpResponse listMl = getLance("/v1/namespace/prod$team_a$ml/list");
    assertSuccess(listMl);
    assertThat(json(listMl).path("namespaces").toString()).contains("embeddings");

    AggregatedHttpResponse listTeamA = getLance("/v1/namespace/prod$team_a/list");
    assertSuccess(listTeamA);
    assertThat(json(listTeamA).path("namespaces").toString()).contains("ml");
    assertThat(json(listTeamA).path("namespaces").toString()).doesNotContain("embeddings");

    AggregatedHttpResponse listProd = getLance("/v1/namespace/prod/list");
    assertSuccess(listProd);
    assertThat(json(listProd).path("namespaces").toString()).contains("team_a");
    assertThat(json(listProd).path("namespaces").toString()).doesNotContain("ml", "embeddings");
  }

  @Test
  @DisplayName("P1-ID-002 custom delimiter resolves to the same canonical namespace path")
  void customDelimiterResolvesToSameCanonicalNamespacePath() throws Exception {
    assertSuccess(postJson("/v1/namespace/prod/create", createNamespaceRequest()));

    AggregatedHttpResponse createWithDefaultDelimiter =
        postJson("/v1/namespace/prod$team_a/create", createNamespaceRequest());
    assertSuccess(createWithDefaultDelimiter);

    AggregatedHttpResponse existsWithDotDelimiter =
        postJson("/v1/namespace/prod.team_a/exists?delimiter=.", "{}");
    assertSuccess(existsWithDotDelimiter);
    assertThat(json(existsWithDotDelimiter).path("exists").asBoolean()).isTrue();

    AggregatedHttpResponse duplicateWithDotDelimiter =
        postJson("/v1/namespace/prod.team_a/create?delimiter=.", createNamespaceRequest());
    assertLanceErrorShape(duplicateWithDotDelimiter, 409);
  }

  @Test
  @DisplayName("P1-ID-004/P1-ID-006 special segments use reversible canonical path encoding")
  void specialCharacterSegmentsRoundTripThroughCanonicalPathKey() throws Exception {
    String encodedNamespaceId = "prod.with.dot$team%2Fa$embedding%20space";

    assertSuccess(postJson("/v1/namespace/prod.with.dot/create", createNamespaceRequest()));
    assertSuccess(
        postJson("/v1/namespace/prod.with.dot$team%2Fa/create", createNamespaceRequest()));

    AggregatedHttpResponse create =
        postJson("/v1/namespace/" + encodedNamespaceId + "/create", createNamespaceRequest());
    assertSuccess(create);

    AggregatedHttpResponse describe =
        postJson("/v1/namespace/" + encodedNamespaceId + "/describe", "{}");
    assertSuccess(describe);

    JsonNode described = json(describe);
    assertThat(described.path("id").asText()).isEqualTo(encodedNamespaceId);

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Object pathKey =
          session
              .createNativeQuery(
                  "select path_key from uc_lance_namespaces where path_key = :pathKey")
              .setParameter("pathKey", "prod.with.dot/team%2Fa/embedding%20space")
              .getSingleResult();

      assertThat(pathKey.toString()).isEqualTo("prod.with.dot/team%2Fa/embedding%20space");
    }
  }

  @Test
  @DisplayName("P1-NS-003/P1-ID-005 invalid or missing parent namespace returns stable error")
  void missingParentNamespaceReturnsStableError() throws Exception {
    AggregatedHttpResponse response =
        postJson("/v1/namespace/missing_parent$child/create", createNamespaceRequest());

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("type").asText()).containsIgnoringCase("not");
  }

  @Test
  @DisplayName("P1-ID-005 invalid namespace identifiers return stable errors without writes")
  void invalidNamespaceIdentifiersReturnStableErrorsAndDoNotPersist() throws Exception {
    AggregatedHttpResponse emptySegment =
        postJson("/v1/namespace/prod$$bad/create", createNamespaceRequest());
    assertLanceErrorShape(emptySegment, 400);
    assertThat(json(emptySegment).path("message").asText()).containsIgnoringCase("empty segment");

    AggregatedHttpResponse badEncoding =
        postJson("/v1/namespace/prod%25ZZ/create", createNamespaceRequest());
    assertLanceErrorShape(badEncoding, 400);
    assertThat(json(badEncoding).path("message").asText()).containsIgnoringCase("encoding");

    AggregatedHttpResponse invalidDelimiter =
        postJson("/v1/namespace/prod::team/create?delimiter=::", createNamespaceRequest());
    assertLanceErrorShape(invalidDelimiter, 400);
    assertThat(json(invalidDelimiter).path("message").asText()).containsIgnoringCase("delimiter");

    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Number count =
          (Number)
              session
                  .createNativeQuery("select count(*) from uc_lance_namespaces")
                  .getSingleResult();
      assertThat(count.longValue()).isZero();
    }
  }

  @Test
  @DisplayName("P1-NS-008 exists returns false for missing namespace without throwing 404")
  void existsReturnsFalseForNonExistentNamespace() throws Exception {
    AggregatedHttpResponse response = postJson("/v1/namespace/nonexistent_namespace/exists", "{}");

    assertSuccess(response);
    assertThat(json(response).path("exists").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P1-NS-009/P1-NS-010/P1-NS-013 restrict drop enforces empty namespace semantics")
  void restrictDropRejectsNamespaceContainingChildOrDeclaredOnlyTable() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));

    AggregatedHttpResponse nonEmptyDrop =
        postJson("/v1/namespace/" + CHILD_NAMESPACE + "/drop", "{\"mode\":\"restrict\"}");
    assertLanceErrorShape(nonEmptyDrop, 409);
    assertThat(json(nonEmptyDrop).path("message").asText()).containsIgnoringCase("not empty");

    assertSuccess(
        postJson("/v1/table/" + DECLARED_TABLE_ID + "/drop", "{\"mode\":\"metadata_only\"}"));
    assertSuccess(
        postJson("/v1/namespace/" + CHILD_NAMESPACE + "/drop", "{\"mode\":\"restrict\"}"));
  }

  @Test
  @DisplayName("P1-NS-011 cascade drop with registered table is not supported in Phase 1")
  void cascadeDropWithRegisteredTableReturnsPhaseLimitedError() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    AggregatedHttpResponse response =
        postJson("/v1/namespace/" + CHILD_NAMESPACE + "/drop", "{\"mode\":\"cascade\"}");

    assertLanceErrorShape(response, 501);
    assertThat(json(response).path("message").asText()).containsIgnoringCase("not supported");
  }

  @Test
  @DisplayName("P1-NS-012 child namespace creation checks parent namespace authorization")
  void childNamespaceCreationChecksParentAuthorization() throws Exception {
    // Root namespace requires admin
    String adminToken = createInternalBearerToken("admin");
    String unauthorizedToken = createInternalBearerToken("unauthorized-phase1-user");

    assertSuccess(
        postJson(
            "/v1/namespace/" + ROOT_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + adminToken)));

    // Unauthorized user cannot create child namespace (admin is the owner)
    AggregatedHttpResponse response =
        postJson(
            "/v1/namespace/" + CHILD_NAMESPACE + "/create",
            createNamespaceRequest(),
            Map.of("Authorization", "Bearer " + unauthorizedToken));

    assertLanceErrorShape(response, 403);
    assertThat(json(response).path("message").asText()).contains("USE_NAMESPACE");
  }

  @Test
  @DisplayName("P1-NS-014 namespace list pagination is stable and non-overlapping")
  void namespaceListPaginationIsStable() throws Exception {
    assertSuccess(
        postJson("/v1/namespace/" + ROOT_NAMESPACE + "/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_a/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_b/create", createNamespaceRequest()));
    assertSuccess(postJson("/v1/namespace/prod$team_c/create", createNamespaceRequest()));

    AggregatedHttpResponse firstPage = getLance("/v1/namespace/prod/list?limit=2");
    assertSuccess(firstPage);
    String nextPageToken = json(firstPage).path("nextPageToken").asText();
    assertThat(nextPageToken).isNotBlank();

    AggregatedHttpResponse secondPage =
        getLance("/v1/namespace/prod/list?limit=2&pageToken=" + nextPageToken);
    assertSuccess(secondPage);
    assertThat(json(secondPage).path("namespaces").toString())
        .doesNotContain(json(firstPage).path("namespaces").get(0).asText());
  }
}
