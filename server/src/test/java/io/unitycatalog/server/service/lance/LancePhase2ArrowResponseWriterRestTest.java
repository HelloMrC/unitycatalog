package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2ArrowResponseWriterRestTest extends BaseLancePhase2RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("P2-DATA-001 query returns consumable Arrow IPC")
  void queryReturnsConsumableArrowIpc() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");

    assertArrowResponse(response);
    assertArrowMagic(response);
  }

  @Test
  @DisplayName("P2-ARROW-007 query returns Arrow file response by default")
  void queryReturnsArrowFileResponseByDefault() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}");

    assertArrowResponse(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .contains(ARROW_FILE.toString());
  }

  @Test
  @DisplayName("P2-ARROW-008 query can negotiate Arrow stream response")
  void queryCanNegotiateArrowStreamResponse() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/query",
            "{}",
            Map.of(HttpHeaderNames.ACCEPT.toString(), ARROW_STREAM.toString()));

    assertArrowResponse(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .contains(ARROW_STREAM.toString());
  }

  @Test
  @DisplayName("P2-ARROW-009 pre-streaming resolver failures return JSON errors")
  void preStreamingResolverFailuresReturnJsonErrors() throws Exception {
    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/prod$team_a$missing/query", "{}");

    assertLanceErrorShape(response, 404);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("json");
  }

  @Test
  @DisplayName("Phase 2 query keeps JSON command echo when JSON is explicitly accepted")
  void queryKeepsJsonCommandEchoWhenJsonIsExplicitlyAccepted() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{\"columns\":[\"id\"]}");

    assertSuccess(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("json");
    assertThat(json(response).path("command").path("querySpec").toString()).contains("id");
  }

  private void assertArrowMagic(AggregatedHttpResponse response) {
    String body = response.content().toString(StandardCharsets.UTF_8);
    assertThat(body).startsWith("ARROW1").endsWith("ARROW1");
  }
}
