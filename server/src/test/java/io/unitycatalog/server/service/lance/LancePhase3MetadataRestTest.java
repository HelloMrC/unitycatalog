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
class LancePhase3MetadataRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  // ========== P3-VERSION: Version 查询测试 ==========

  @Test
  @DisplayName("P3-VERSION-001: listVersions returns version history")
  void versionListReturnsVersionHistory() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/list", "{\"page_size\":1}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("versions")).hasSize(1);
    assertThat(list.path("versions").get(0).path("version").asLong()).isEqualTo(1L);
    assertThat(list.path("versions").get(0).path("operation").asText()).isEqualTo("insert");
    assertThat(list.path("versions").get(0).path("stats").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-VERSION-002: listVersions returns empty for declared-only table")
  void versionListReturnsEmptyForDeclaredTable() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_DECLARED_TABLE_ID + "/version/list", "{}");

    assertSuccess(listResponse);
    assertThat(json(listResponse).path("versions").isArray()).isTrue();
    assertThat(json(listResponse).path("versions")).isEmpty();
  }

  @Test
  @DisplayName("P3-VERSION-003: describeVersion returns version details")
  void versionDescribeReturnsVersionDetails() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse describeResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":1}");

    assertSuccess(describeResponse);
    JsonNode version = json(describeResponse);
    assertThat(version.path("version").asLong()).isEqualTo(1L);
    assertThat(version.path("operation").asText()).isEqualTo("insert");
    assertThat(version.path("stats").isObject()).isTrue();
  }

  @Test
  @DisplayName("P3-VERSION-004: describeVersion returns 404 for non-existent version")
  void versionDescribeReturnsNotFoundForMissingVersion() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":99}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("not found");
  }

  // ========== P3-TAG: Tag CRUD 测试 ==========

  @Test
  @DisplayName("P3-TAG-001: listTags returns tag list")
  void tagListReturnsTagList() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"release-1.0\",\"version\":1}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"dev-latest\",\"version\":1}"));

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/list", "{\"page_size\":1}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("tags")).hasSize(1);
    assertThat(list.path("next_page_token").asText()).isEqualTo("dev-latest");
  }

  @Test
  @DisplayName("P3-TAG-002: listTags returns empty for table without tags")
  void tagListReturnsEmptyForNoTags() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/list", "{}");

    assertSuccess(listResponse);
    assertThat(json(listResponse).path("tags").isArray()).isTrue();
    assertThat(json(listResponse).path("tags")).isEmpty();
  }

  @Test
  @DisplayName("P3-TAG-003: getTagVersion returns tag version")
  void tagGetVersionReturnsTagVersion() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"release-1.0\",\"version\":1,"
                + "\"metadata\":{\"description\":\"first release\"}}"));

    AggregatedHttpResponse getResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"release-1.0\"}");

    assertSuccess(getResponse);
    JsonNode tag = json(getResponse);
    assertThat(tag.path("tag_name").asText()).isEqualTo("release-1.0");
    assertThat(tag.path("version").asLong()).isEqualTo(1L);
    assertThat(tag.path("metadata").path("description").asText()).isEqualTo("first release");
  }

  @Test
  @DisplayName("P3-TAG-004: getTagVersion returns 404 for non-existent tag")
  void tagGetVersionReturnsNotFoundForMissingTag() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"missing\"}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("not found");
  }

  @Test
  @DisplayName("P3-TAG-005: createTag creates tag metadata")
  void tagCreateCreatesTagMetadata() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse createResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"v1.0\",\"version\":1,\"metadata\":{\"notes\":\"production\"}}");

    assertSuccess(createResponse);
    JsonNode tag = json(createResponse);
    assertThat(tag.path("tag_name").asText()).isEqualTo("v1.0");
    assertThat(tag.path("version").asLong()).isEqualTo(1L);
    assertThat(tag.path("metadata").path("notes").asText()).isEqualTo("production");
  }

  @Test
  @DisplayName("P3-TAG-006: createTag returns 404 for non-existent version")
  void tagCreateReturnsNotFoundForMissingVersion() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"bad-tag\",\"version\":99}");

    assertLanceErrorShape(response, 404);
    assertThat(json(response).path("message").asText()).contains("version not found");
  }

  @Test
  @DisplayName("P3-TAG-007: createTag returns 409 for duplicate tag")
  void tagCreateReturnsConflictForDuplicateTag() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"duplicate\",\"version\":1}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"duplicate\",\"version\":1}");

    assertLanceErrorShape(response, 409);
    assertThat(json(response).path("message").asText()).contains("already exists");
  }

  @Test
  @DisplayName("P3-TAG-008: updateTag updates tag metadata")
  void tagUpdateUpdatesTagMetadata() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"release-1.0\",\"version\":1}"));

    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/update",
            "{\"tag_name\":\"release-1.0\",\"new_version\":1,"
                + "\"new_metadata\":{\"description\":\"updated\"}}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("metadata").path("description").asText())
        .isEqualTo("updated");
  }

  @Test
  @DisplayName("P3-TAG-009: deleteTag deletes tag metadata")
  void tagDeleteDeletesTagMetadata() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"to-delete\",\"version\":1}"));

    AggregatedHttpResponse deleteResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/delete", "{\"tag_name\":\"to-delete\"}");

    assertSuccess(deleteResponse);
    assertThat(json(deleteResponse).path("deleted").asBoolean()).isTrue();

    AggregatedHttpResponse getResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"to-delete\"}");
    assertLanceErrorShape(getResponse, 404);
  }

  // ========== P3-TAG-ALIAS: Tag alias 测试 ==========

  @Test
  @DisplayName("P3-TAG-ALIAS-001: tags/get-version alias works")
  void tagAliasGetVersionWorks() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"alias-test\",\"version\":1}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get-version",
            "{\"tag_name\":\"alias-test\"}");

    assertSuccess(response);
    assertThat(json(response).path("tag_name").asText()).isEqualTo("alias-test");
    assertThat(json(response).path("version").asLong()).isEqualTo(1L);
  }

  @Test
  @DisplayName("P3-TAG-ALIAS-002: tags/version alias works")
  void tagAliasVersionWorks() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"alias-version\",\"version\":1}"));

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/version",
            "{\"tag_name\":\"alias-version\"}");

    assertSuccess(response);
    assertThat(json(response).path("tag_name").asText()).isEqualTo("alias-version");
    assertThat(json(response).path("version").asLong()).isEqualTo(1L);
  }

  // ========== P3-ERROR: 语义错误拒绝测试 ==========

  @Test
  @DisplayName("P3-ERROR-001: createVersion returns 400 BAD_REQUEST")
  void createVersionReturnsBadRequest() throws Exception {
    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/create", "{\"version\":10}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("cannot be created manually");
  }

  @Test
  @DisplayName("P3-ERROR-002: batchCreateVersions returns 400 BAD_REQUEST")
  void batchCreateVersionsReturnsBadRequest() throws Exception {
    AggregatedHttpResponse response =
        postJson("/v1/table/batch-create-versions", "{\"versions\":[1,2,3]}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText())
        .contains("cannot be batch-created manually");
  }

  @Test
  @DisplayName("P3-ERROR-003: deleteVersion forwards to backend execution")
  void deleteVersionReturnsUnimplemented() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/delete", "{\"versions\":[1]}");

    // With echo backend, deleteVersions now succeeds and returns mock result
    assertSuccess(response);
    assertThat(json(response).path("deleted_versions").asInt()).isEqualTo(0);
  }
}
