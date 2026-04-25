package io.unitycatalog.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import io.unitycatalog.server.base.BaseServerTest;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanceNamespaceRestServiceTest extends BaseServerTest {
  private static final String LANCE_API_PREFIX = "/api/2.1/unity-catalog/lance";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private WebClient client;

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    client = WebClient.builder(serverConfig.getServerUrl()).build();
  }

  @Test
  void createDescribeAndExistsNamespace() throws Exception {
    AggregatedHttpResponse create =
        postJson("/v1/namespace/prod/create", "{\"properties\":{\"purpose\":\"phase1-test\"}}");
    assertSuccess(create);

    AggregatedHttpResponse describe = postJson("/v1/namespace/prod/describe", "{}");
    assertSuccess(describe);
    JsonNode described = json(describe);
    assertThat(described.path("id").asText()).isEqualTo("prod");
    assertThat(described.path("properties").path("purpose").asText()).isEqualTo("phase1-test");

    AggregatedHttpResponse exists = postJson("/v1/namespace/prod/exists", "{}");
    assertSuccess(exists);
    assertThat(json(exists).path("exists").asBoolean()).isTrue();
  }

  @Test
  void listNamespacesRespectsDirectChildrenAndDelimiter() throws Exception {
    assertSuccess(postJson("/v1/namespace/prod/create", "{\"properties\":{}}"));
    assertSuccess(postJson("/v1/namespace/prod$team_b/create", "{\"properties\":{}}"));
    assertSuccess(postJson("/v1/namespace/prod$team_a/create", "{\"properties\":{}}"));

    AggregatedHttpResponse defaultDelimiter = getLance("/v1/namespace/prod/list");
    assertSuccess(defaultDelimiter);
    assertThat(json(defaultDelimiter).path("namespaces").toString())
        .contains("prod$team_a", "prod$team_b");

    AggregatedHttpResponse customDelimiter = getLance("/v1/namespace/prod/list?delimiter=.");
    assertSuccess(customDelimiter);
    assertThat(json(customDelimiter).path("namespaces").toString())
        .contains("prod.team_a", "prod.team_b");
  }

  private AggregatedHttpResponse getLance(String path) {
    return client.get(LANCE_API_PREFIX + path).aggregate().join();
  }

  private AggregatedHttpResponse postJson(String path, String body) {
    return postJson(path, body, Map.of());
  }

  private AggregatedHttpResponse postJson(String path, String body, Map<String, String> headers) {
    RequestHeadersBuilder builder =
        RequestHeaders.builder()
            .method(HttpMethod.POST)
            .path(LANCE_API_PREFIX + path)
            .contentType(MediaType.JSON)
            .add(HttpHeaderNames.ACCEPT, MediaType.JSON.toString());
    headers.forEach(builder::add);
    return client.execute(builder.build(), HttpData.ofUtf8(body)).aggregate().join();
  }

  private JsonNode json(AggregatedHttpResponse response) throws IOException {
    return objectMapper.readTree(response.contentUtf8());
  }

  private void assertSuccess(AggregatedHttpResponse response) {
    assertThat(response.status().code()).isBetween(200, 299);
  }
}
