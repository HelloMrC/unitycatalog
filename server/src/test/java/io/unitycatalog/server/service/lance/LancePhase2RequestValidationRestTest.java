package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LancePhase2RequestValidationRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
    serverProperties.setProperty(Property.LANCE_EXECUTION_MAX_JSON_REQUEST_BYTES.getKey(), "1024");
    serverProperties.setProperty(Property.LANCE_EXECUTION_MAX_ARROW_REQUEST_BYTES.getKey(), "1024");
  }

  @Test
  @DisplayName("Phase 2 JSON endpoints reject non-JSON content type")
  void jsonEndpointsRejectNonJsonContentType() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", arrowSmallStreamFixture());

    assertLanceErrorShape(response, 415);
    assertThat(json(response).path("type").asText()).isEqualTo("unsupported_media_type");
    assertThat(response.contentUtf8()).doesNotContain("test-echo");
  }

  @Test
  @DisplayName("Phase 2 Arrow endpoints reject non-Arrow content type")
  void arrowEndpointsRejectNonArrowContentType() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", "{\"rows\":[]}");

    assertLanceErrorShape(response, 415);
    assertThat(json(response).path("type").asText()).isEqualTo("unsupported_media_type");
    assertThat(response.contentUtf8()).doesNotContain("test-echo");
  }

  @Test
  @DisplayName("Phase 2 oversized JSON body returns 413")
  void oversizedJsonBodyReturns413() throws Exception {
    createActiveTableFixture();
    String oversizedBody = "{\"filter\":\"" + "x".repeat(2048) + "\"}";

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", oversizedBody);

    assertLanceErrorShape(response, 413);
    assertThat(json(response).path("type").asText()).isEqualTo("request_entity_too_large");
    assertThat(response.contentUtf8()).doesNotContain("test-echo");
  }

  @Test
  @DisplayName("Phase 2 oversized Arrow body returns 413")
  void oversizedArrowBodyReturns413() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowLargeStreamFixture());

    assertLanceErrorShape(response, 413);
    assertThat(json(response).path("type").asText()).isEqualTo("request_entity_too_large");
    assertThat(response.contentUtf8()).doesNotContain("test-echo");
  }
}
