package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

abstract class BaseLancePhase2RestTest extends BaseLancePhase1RestTest {
  protected static final MediaType ARROW_STREAM =
      MediaType.parse("application/vnd.apache.arrow.stream");
  protected static final MediaType ARROW_FILE =
      MediaType.parse("application/vnd.apache.arrow.file");
  protected static final String P2_ACTIVE_TABLE_ID = TABLE_ID;
  protected static final String P2_DECLARED_TABLE_ID = DECLARED_TABLE_ID;
  protected static final String P2_DEREGISTERED_TABLE_ID = "prod$team_a$deregistered";
  protected static final String P2_DROPPED_TABLE_ID = "prod$team_a$dropped";
  protected static final String P2_LEGACY_TABLE_ID = LEGACY_TABLE_FULL_NAME;
  protected static final String P2_NON_LANCE_TEXT_TABLE_ID = NON_LANCE_TABLE_FULL_NAME;

  private WebClient phase2Client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    phase2Client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  protected AggregatedHttpResponse requestLance(
      HttpMethod method, String path, MediaType contentType, byte[] body) {
    return requestLance(method, path, contentType, body, Map.of());
  }

  protected AggregatedHttpResponse requestLance(
      HttpMethod method,
      String path,
      MediaType contentType,
      byte[] body,
      Map<String, String> headers) {
    RequestHeadersBuilder builder =
        RequestHeaders.builder().method(method).path(LANCE_API_PREFIX + path);
    if (contentType != null) {
      builder.contentType(contentType);
    }
    headers.forEach(builder::add);
    RequestHeaders requestHeaders = builder.build();
    if (body == null) {
      return phase2Client.execute(requestHeaders).aggregate().join();
    }
    return phase2Client.execute(requestHeaders, HttpData.wrap(body)).aggregate().join();
  }

  protected AggregatedHttpResponse postJsonWithHeaders(
      String path, String body, Map<String, String> headers) {
    return requestLance(
        HttpMethod.POST, path, MediaType.JSON, body.getBytes(StandardCharsets.UTF_8), headers);
  }

  protected AggregatedHttpResponse postArrow(String path, byte[] body) {
    return postArrow(path, body, Map.of());
  }

  protected AggregatedHttpResponse postArrow(
      String path, byte[] body, Map<String, String> headers) {
    return requestLance(HttpMethod.POST, path, ARROW_STREAM, body, headers);
  }

  protected AggregatedHttpResponse postArrowExpectingStream(String path, byte[] body) {
    return postArrow(
        path, body, Map.of(HttpHeaderNames.ACCEPT.toString(), ARROW_STREAM.toString()));
  }

  protected AggregatedHttpResponse postQueryExpectingArrow(String path, String body) {
    return postJsonWithHeaders(
        path, body, Map.of(HttpHeaderNames.ACCEPT.toString(), ARROW_FILE.toString()));
  }

  protected void createActiveTableFixture() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/register", declareTableRequest(TABLE_LOCATION)));
  }

  protected void createDeclaredTableFixture() {
    createRootAndChildNamespaces();
    assertSuccess(
        postJson(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));
  }

  protected byte[] arrowSmallStreamFixture() {
    return "phase2-arrow-small-stream-fixture".getBytes(StandardCharsets.UTF_8);
  }

  protected byte[] arrowLargeStreamFixture() {
    byte[] bytes = new byte[2 * 1024 * 1024];
    Arrays.fill(bytes, (byte) 'x');
    return bytes;
  }

  protected String basicQueryRequest() {
    return "{}";
  }

  protected String fullQuerySpecRequest() {
    return "{"
        + "\"columns\":[\"id\",\"text\"],"
        + "\"filter\":\"id > 1\","
        + "\"vector\":[0.1,0.2,0.3],"
        + "\"vectorColumn\":\"vector\","
        + "\"distanceType\":\"cosine\","
        + "\"k\":10,"
        + "\"offset\":5,"
        + "\"bounds\":{\"lower\":0.1,\"upper\":0.9},"
        + "\"prefilter\":true,"
        + "\"ef\":64,"
        + "\"nprobes\":16,"
        + "\"refineFactor\":4,"
        + "\"fastSearch\":true,"
        + "\"bypassVectorIndex\":false,"
        + "\"withRowId\":true,"
        + "\"version\":1"
        + "}";
  }

  protected String mergeInsertAllParametersRequest() {
    return "{"
        + "\"on\":[\"id\"],"
        + "\"whenMatchedUpdateAll\":true,"
        + "\"whenMatchedUpdateAllFilt\":\"source.updated_at > target.updated_at\","
        + "\"whenNotMatchedInsertAll\":true,"
        + "\"whenNotMatchedBySourceDelete\":true,"
        + "\"whenNotMatchedBySourceDeleteFilt\":\"target.expired = true\","
        + "\"timeout\":30000,"
        + "\"useIndex\":true"
        + "}";
  }

  protected JsonNode assertCommandEcho(AggregatedHttpResponse response) throws IOException {
    assertSuccess(response);
    JsonNode body = json(response);
    assertThat(body.path("command").isObject()).as(response.contentUtf8()).isTrue();
    return body.path("command");
  }

  protected void assertArrowResponse(AggregatedHttpResponse response) {
    assertSuccess(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .as(response.headers().toString())
        .contains("application/vnd.apache.arrow");
    assertThat(response.content().length()).isGreaterThan(0);
  }

  protected void assertLanceErrorShapeAllowingUnmounted(AggregatedHttpResponse response)
      throws IOException {
    assertThat(response.status().code()).isIn(404, 501);
    if (response.headers().get(HttpHeaderNames.CONTENT_TYPE) != null
        && response.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("json")
        && !response.contentUtf8().isBlank()) {
      assertThat(json(response).has("message")).as(response.contentUtf8()).isTrue();
    }
  }
}
