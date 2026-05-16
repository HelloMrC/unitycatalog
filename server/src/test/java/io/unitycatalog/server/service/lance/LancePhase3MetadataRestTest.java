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

  @Test
  @DisplayName("Phase 3 version list and describe read synced write metadata")
  void versionListAndDescribeReadSyncedWriteMetadata() throws Exception {
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

    AggregatedHttpResponse describeResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/describe", "{\"version\":1}");

    assertSuccess(describeResponse);
    JsonNode version = json(describeResponse);
    assertThat(version.path("version").asLong()).isEqualTo(1L);
    assertThat(version.path("operation").asText()).isEqualTo("insert");
    assertThat(version.path("stats").isObject()).isTrue();
  }

  @Test
  @DisplayName("Phase 3 tag metadata CRUD uses synced Lance versions")
  void tagMetadataCrudUsesSyncedLanceVersions() throws Exception {
    createActiveTableFixture();
    assertSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    AggregatedHttpResponse missingVersion =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"missing\",\"version\":99}");
    assertLanceErrorShape(missingVersion, 404);

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"release-1.0\",\"version\":1,"
                + "\"metadata\":{\"description\":\"first release\"}}"));
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/create",
            "{\"tag_name\":\"dev-latest\",\"version\":1}"));

    AggregatedHttpResponse getResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get-version",
            "{\"tag_name\":\"release-1.0\"}");

    assertSuccess(getResponse);
    JsonNode tag = json(getResponse);
    assertThat(tag.path("tag_name").asText()).isEqualTo("release-1.0");
    assertThat(tag.path("version").asLong()).isEqualTo(1L);
    assertThat(tag.path("metadata").path("description").asText()).isEqualTo("first release");

    AggregatedHttpResponse listResponse =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/list", "{\"page_size\":1}");

    assertSuccess(listResponse);
    JsonNode list = json(listResponse);
    assertThat(list.path("tags")).hasSize(1);
    assertThat(list.path("tags").get(0).path("tag_name").asText()).isEqualTo("dev-latest");
    assertThat(list.path("next_page_token").asText()).isEqualTo("dev-latest");

    AggregatedHttpResponse updateResponse =
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/update",
            "{\"tag_name\":\"release-1.0\",\"new_version\":1,"
                + "\"new_metadata\":{\"description\":\"updated release\"}}");

    assertSuccess(updateResponse);
    assertThat(json(updateResponse).path("metadata").path("description").asText())
        .isEqualTo("updated release");

    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/delete", "{\"tag_name\":\"release-1.0\"}"));
    assertLanceErrorShape(
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/tags/get", "{\"tag_name\":\"release-1.0\"}"),
        404);
  }

  @Test
  @DisplayName("Phase 3 unsupported manual version creation returns semantic error")
  void unsupportedManualVersionCreationReturnsSemanticError() throws Exception {
    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/version/create", "{\"version\":10}");

    assertLanceErrorShape(response, 400);
    assertThat(json(response).path("message").asText()).contains("cannot be created manually");
  }
}
