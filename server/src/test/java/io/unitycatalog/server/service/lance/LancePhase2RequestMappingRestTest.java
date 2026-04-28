package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import io.unitycatalog.server.service.lance.backend.LanceTestEchoExecutionBackend;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LancePhase2RequestMappingRestTest extends BaseLancePhase1RestTest {

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_BACKEND_CLASS.getKey(),
        LanceTestEchoExecutionBackend.class.getName());
  }

  @Test
  @DisplayName("Phase 2 JSON data request fields are mapped into backend commands")
  void jsonDataRequestFieldsAreMappedIntoBackendCommands() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJson(
            "/v1/table/" + TABLE_ID + "/count_rows",
            "{"
                + "\"id\":\"prod$team_a$spoofed\","
                + "\"predicate\":\"id > 0\","
                + "\"version\":2,"
                + "\"identity\":{\"principal\":\"evil@example.com\"}"
                + "}");

    JsonNode command = command(response);
    assertThat(command.path("tableId").asText()).isEqualTo(TABLE_ID);
    assertThat(command.path("predicate").asText()).isEqualTo("id > 0");
    assertThat(command.path("version").asInt()).isEqualTo(2);
    assertThat(command.toString()).doesNotContain("spoofed", "evil@example.com");
  }

  @Test
  @DisplayName("Phase 2 request context and request id are propagated")
  void requestContextAndRequestIdArePropagated() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postJsonWithHeaders(
            "/v1/table/" + TABLE_ID + "/query",
            "{\"columns\":[\"id\",\"text\"],\"filter\":\"id > 1\"}",
            Map.of("x-request-id", "phase2-request-42", "x-lance-tenant-id", "tenant-a"));

    JsonNode command = command(response);
    assertThat(command.path("requestId").asText()).isEqualTo("phase2-request-42");
    assertThat(command.path("context").path("tenantId").asText()).isEqualTo("tenant-a");
    assertThat(command.path("querySpec").toString()).contains("columns", "filter", "id > 1");
    assertThat(response.headers().get("x-request-id")).isEqualTo("phase2-request-42");
  }

  @Test
  @DisplayName("Phase 2 Arrow requests expose Arrow input metadata and hashed idempotency keys")
  void arrowRequestsExposeInputMetadataAndHashedIdempotencyKeys() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + TABLE_ID + "/insert",
            "arrow-fixture".getBytes(StandardCharsets.UTF_8),
            Map.of("Idempotency-Key", "plain-idempotency-key"));

    JsonNode command = command(response);
    assertThat(command.path("inputData").asText()).isEqualTo("arrow-stream");
    assertThat(command.path("inputBytes").asInt()).isGreaterThan(0);
    assertThat(command.path("idempotencyKeyHash").asText()).isNotBlank();
    assertThat(command.toString()).doesNotContain("plain-idempotency-key");
  }

  private JsonNode command(AggregatedHttpResponse response) throws Exception {
    assertSuccess(response);
    return json(response).path("command");
  }

  private AggregatedHttpResponse postJsonWithHeaders(
      String path, String body, Map<String, String> headers) {
    RequestHeadersBuilder builder =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(LANCE_API_PREFIX + path)
            .contentType(MediaType.JSON)
            .add(HttpHeaderNames.ACCEPT, MediaType.JSON.toString());
    headers.forEach(builder::add);
    return client().execute(builder.build(), HttpData.ofUtf8(body)).aggregate().join();
  }

  private AggregatedHttpResponse postArrow(String path, byte[] body, Map<String, String> headers) {
    RequestHeadersBuilder builder =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(LANCE_API_PREFIX + path)
            .contentType(MediaType.parse("application/vnd.apache.arrow.stream"));
    headers.forEach(builder::add);
    return client().execute(builder.build(), HttpData.wrap(body)).aggregate().join();
  }

  private com.linecorp.armeria.client.WebClient client() {
    return com.linecorp.armeria.client.WebClient.builder(serverConfig.getServerUrl()).build();
  }

  private void createActiveTableFixture() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson("/v1/table/" + TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
  }
}
