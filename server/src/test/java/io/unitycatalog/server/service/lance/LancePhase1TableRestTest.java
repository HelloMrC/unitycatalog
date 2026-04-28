package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase1")
class LancePhase1TableRestTest extends BaseLancePhase1RestTest {

  @Test
  @DisplayName("P1-CRED-001/P1-CRED-002 describe body vend_credentials controls storage_options")
  void describeBodyVendCredentialsControlsStorageOptions() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    AggregatedHttpResponse withoutCredentials =
        postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":false}");
    assertSuccess(withoutCredentials);
    assertThat(json(withoutCredentials).has("storage_options")).isFalse();

    AggregatedHttpResponse withCredentials =
        postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":true}");
    assertSuccess(withCredentials);
    assertThat(json(withCredentials).path("storage_options").isObject()).isTrue();
  }

  @Test
  @DisplayName("P1-CRED-003 declared-only table can vend credentials without physical metadata")
  void declaredOnlyTableCanVendCredentialsWithoutPhysicalMetadata() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + DECLARED_TABLE_ID + "/describe", "{\"vend_credentials\":true}");

    assertSuccess(response);
    assertThat(json(response).path("is_only_declared").asBoolean()).isTrue();
    assertThat(json(response).path("storage_options").isObject()).isTrue();
    assertThat(json(response).path("physical_metadata_loaded").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P1-CRED-008/P1-CRED-009 storage template excludes temporary secret fields")
  void storageOptionsTemplateExcludesTemporarySecretFields() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + TABLE_ID + "/register",
            "{"
                + "\"location\":\""
                + TABLE_LOCATION
                + "\","
                + "\"storage_options_template\":{"
                + "\"provider\":\"s3\","
                + "\"region\":\"us-west-2\","
                + "\"session_token\":\"secret-session\","
                + "\"access_key_id\":\"secret-key\""
                + "},"
                + "\"properties\":{\"table_type\":\"lance\"}"
                + "}"));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + TABLE_ID + "/describe", "{\"vend_credentials\":true}");
    assertSuccess(response);

    String persistedTemplate = json(response).path("storage_options_template").toString();
    assertThat(persistedTemplate).contains("provider", "region");
    assertThat(persistedTemplate)
        .doesNotContain("token", "session", "secret", "expires", "access_key");
    assertThat(json(response).path("storage_options").toString())
        .contains("provider", "region")
        .doesNotContain("secret");
  }

  @Test
  @DisplayName("P1-TBL-001 empty namespace table list returns empty collection")
  void emptyNamespaceTableListReturnsEmptyCollection() throws Exception {
    createRootAndChildNamespaces();

    AggregatedHttpResponse response = getLance("/v1/namespace/" + CHILD_NAMESPACE + "/table/list");

    assertSuccess(response);
    assertThat(json(response).path("tables").isArray()).isTrue();
    assertThat(json(response).path("tables").size()).isEqualTo(0);
  }

  @Test
  @DisplayName("P1-ID-005 invalid table identifier returns stable invalid request")
  void invalidTableIdentifierReturnsStableError() throws Exception {
    AggregatedHttpResponse response =
        postJson("/v1/table/root_only/register", declareTableRequest(TABLE_LOCATION));

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText())
        .containsIgnoringCase("namespace")
        .containsIgnoringCase("table name");
  }

  @Test
  @DisplayName("P1-TBL-002/P1-TBL-005/P1-TBL-009 register, list, and describe table")
  void registerListAndDescribeTable() throws Exception {
    createRootAndChildNamespaces();

    AggregatedHttpResponse register =
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION));
    assertSuccess(register);

    AggregatedHttpResponse list = getLance("/v1/namespace/" + CHILD_NAMESPACE + "/table/list");
    assertSuccess(list);
    assertThat(json(list).path("tables").toString()).contains(TABLE_ID);

    AggregatedHttpResponse describe = postJson("/v1/table/" + TABLE_ID + "/describe", "{}");
    assertSuccess(describe);
    JsonNode table = json(describe);
    assertThat(table.path("id").asText()).isEqualTo(TABLE_ID);
    assertThat(table.path("location").asText()).isEqualTo(TABLE_LOCATION);
    assertThat(table.path("state").asText()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("P1-ID-007 table list emits protocol full path strings that round-trip")
  void tableListEmitsProtocolFullPathStringsThatRoundTrip() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    AggregatedHttpResponse list = getLance("/v1/namespace/prod.team_a/table/list?delimiter=.");
    assertSuccess(list);
    String tables = json(list).path("tables").toString();
    assertThat(tables).contains("prod.team_a.embeddings");
    assertThat(tables).doesNotContain("prod$team_a$embeddings");
  }

  @Test
  @DisplayName("P1-TBL-003/P1-TBL-004 declared-only visibility follows include_declared")
  void declaredOnlyTableVisibilityFollowsIncludeDeclaredFlag() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));

    AggregatedHttpResponse defaultList =
        getLance("/v1/namespace/" + CHILD_NAMESPACE + "/table/list?include_declared=false");
    assertSuccess(defaultList);
    assertThat(json(defaultList).path("tables").toString()).doesNotContain(DECLARED_TABLE_ID);

    AggregatedHttpResponse includeDeclaredList =
        getLance("/v1/namespace/" + CHILD_NAMESPACE + "/table/list?include_declared=true");
    assertSuccess(includeDeclaredList);
    assertThat(json(includeDeclaredList).path("tables").toString()).contains(DECLARED_TABLE_ID);
  }

  @Test
  @DisplayName("P1-TBL-007/P1-TBL-008 declare and create-empty alias are semantically equivalent")
  void declareAndCreateEmptyAliasAreEquivalent() throws Exception {
    createRootAndChildNamespaces();

    AggregatedHttpResponse declare =
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION));
    assertSuccess(declare);

    AggregatedHttpResponse createEmpty =
        postJson(
            "/v1/table/prod$team_a$create_empty_alias/create-empty",
            declareTableRequest("file:///tmp/uc-lance/create-empty-alias.lance"));
    assertSuccess(createEmpty);

    JsonNode declared = json(declare);
    JsonNode alias = json(createEmpty);
    assertThat(declared.path("is_only_declared").asBoolean()).isTrue();
    assertThat(alias.path("is_only_declared").asBoolean()).isTrue();
    assertThat(alias.path("protocol_variant").asText()).isEqualTo("create-empty");
    assertThat(alias.path("deprecated_alias_used").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P1-TBL-006 duplicate register returns already exists error")
  void duplicateRegisterReturnsAlreadyExistsError() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    AggregatedHttpResponse duplicate =
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION));

    assertLanceErrorShape(duplicate, 409);
    assertThat(json(duplicate).path("type").asText()).containsIgnoringCase("already");
  }

  @Test
  @DisplayName("P1-TBL-010/P1-TBL-011 describe and exists support declared-only table")
  void describeAndExistsSupportDeclaredOnlyTable() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));

    AggregatedHttpResponse describe =
        postJson("/v1/table/" + DECLARED_TABLE_ID + "/describe", "{}");
    assertSuccess(describe);
    assertThat(json(describe).path("is_only_declared").asBoolean()).isTrue();

    AggregatedHttpResponse exists = postJson("/v1/table/" + DECLARED_TABLE_ID + "/exists", "{}");
    assertSuccess(exists);
    assertThat(json(exists).path("exists").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("P1-TBL-012/P1-TBL-013 drop supports declared-only and rejects registered table")
  void dropSupportsDeclaredOnlyAndRejectsRegisteredTable() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));
    assertSuccess(
        postJson("/v1/table/" + DECLARED_TABLE_ID + "/drop", "{\"mode\":\"metadata_only\"}"));

    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
    AggregatedHttpResponse registeredDrop =
        postJson("/v1/table/" + TABLE_ID + "/drop", "{\"mode\":\"metadata_only\"}");
    assertLanceErrorShape(registeredDrop, 501);
    assertThat(json(registeredDrop).path("message").asText()).containsIgnoringCase("not supported");
  }

  @Test
  @DisplayName("P1-TBL-014 deregister removes metadata without deleting physical data")
  void deregisterRemovesMetadataWithoutDeletingPhysicalData() throws Exception {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));

    AggregatedHttpResponse deregister =
        postJson("/v1/table/" + TABLE_ID + "/deregister", "{\"delete_physical_data\":false}");
    assertSuccess(deregister);

    AggregatedHttpResponse exists = postJson("/v1/table/" + TABLE_ID + "/exists", "{}");
    assertSuccess(exists);
    assertThat(json(exists).path("exists").asBoolean()).isFalse();
  }
}
