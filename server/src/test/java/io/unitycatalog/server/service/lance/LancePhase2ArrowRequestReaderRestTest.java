package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2ArrowRequestReaderRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
    serverProperties.setProperty(Property.LANCE_EXECUTION_MAX_ARROW_REQUEST_BYTES.getKey(), "1024");
  }

  @Test
  @DisplayName("P2-ARROW-001 insert accepts Arrow stream media type")
  void insertAcceptsArrowStreamMediaType() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
  }

  @Test
  @DisplayName("P2-ARROW-002 merge_insert accepts Arrow stream media type")
  void mergeInsertAcceptsArrowStreamMediaType() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/merge_insert", arrowSmallStreamFixture());

    assertSuccess(response);
  }

  @Test
  @DisplayName("P2-ARROW-003 create accepts Arrow stream media type")
  void createAcceptsArrowStreamMediaType() throws Exception {
    createDeclaredTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/create", arrowSmallStreamFixture());

    assertSuccess(response);
  }

  @Test
  @DisplayName("P2-ARROW-004 non-Arrow body is rejected for Arrow endpoints")
  void nonArrowBodyIsRejectedForArrowEndpoints() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", "{\"rows\":[]}");

    assertLanceErrorShape(response, 415);
    assertThat(json(response).path("type").asText()).isEqualTo("unsupported_media_type");
  }

  @Test
  @DisplayName("P2-ARROW-005 oversized Arrow body returns 413")
  void oversizedArrowBodyReturns413() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowLargeStreamFixture());

    assertLanceErrorShape(response, 413);
    assertThat(json(response).path("type").asText()).isEqualTo("request_entity_too_large");
  }

  @Test
  @DisplayName("P2-ARROW-006 Arrow stream metadata is passed through to backend command")
  void arrowStreamMetadataIsPassedThroughToBackendCommand() throws Exception {
    createActiveTableFixture();

    JsonNode body =
        json(postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertThat(body.path("requestBufferedBytes").asLong()).isLessThan(1024);
    assertThat(body.path("streamPassedThrough").asBoolean()).isTrue();
    assertThat(body.path("command").path("inputData").asText()).isEqualTo("arrow-stream");
    assertThat(body.path("command").path("inputMediaType").asText())
        .isEqualTo(ARROW_STREAM.toString());
  }

  @Test
  @DisplayName("P2-ARROW-010 schema peek is off by default")
  void schemaPeekIsOffByDefault() throws Exception {
    createActiveTableFixture();

    JsonNode body =
        json(postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));

    assertThat(body.path("schemaSource").asText()).isEqualTo("worker");
    assertThat(body.path("schemaPeeked").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-ARROW-011 schema peek reads only schema message when enabled")
  void schemaPeekReadsOnlySchemaMessageWhenEnabled() throws Exception {
    createActiveTableFixture();

    JsonNode body =
        json(
            postArrow(
                "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
                arrowSmallStreamFixture(),
                Map.of("x-lance-test-arrow-peek-schema", "true")));

    assertThat(body.path("schemaPeeked").asBoolean()).isTrue();
    assertThat(body.path("recordBatchesParsedByUc").asInt()).isEqualTo(0);
    assertThat(body.path("schemaSource").asText()).isEqualTo("arrow-peek");
  }
}
