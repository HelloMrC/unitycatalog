package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import io.unitycatalog.server.utils.ServerProperties.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
class LancePhase2RawHttpClientSmokeRestTest extends BaseLancePhase2RestTest {
  private LanceMinimalRealWorkerFixture worker;

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    worker = new LanceMinimalRealWorkerFixture();
    worker.start();
    serverProperties.setProperty(Property.LANCE_EXECUTION_BACKEND_TYPE.getKey(), "worker-http");
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_BASE_URL.getKey(), worker.baseUrl());
    serverProperties.setProperty(
        Property.LANCE_EXECUTION_WORKER_HEALTH_PATH.getKey(),
        LanceMinimalRealWorkerFixture.HEALTH_PATH);
  }

  @AfterEach
  @Override
  public void tearDown() {
    try {
      super.tearDown();
    } finally {
      if (worker != null) {
        worker.close();
      }
    }
  }

  @Test
  @DisplayName("P2-CLIENT-001 raw HTTP covers all 10 data endpoints")
  void rawHttpCoversAllDataEndpoints() throws Exception {
    createActiveTableFixture();

    assertArrowSuccess(postQueryExpectingArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/query", "{}"));
    assertJsonSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/count_rows", "{}"));
    assertJsonSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/stats", "{}"));
    assertJsonSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture()));
    assertJsonSuccess(
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/merge_insert", arrowSmallStreamFixture()));
    assertJsonSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/update", "{\"updates\":{}}"));
    assertJsonSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/delete", "{}"));
    assertJsonSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/explain_plan", "{}"));
    assertJsonSuccess(postJson("/v1/table/" + P2_ACTIVE_TABLE_ID + "/analyze_plan", "{}"));

    assertSuccess(
        postJson(
            "/v1/table/" + P2_DECLARED_TABLE_ID + "/declare",
            declareTableRequest(DECLARED_TABLE_LOCATION)));
    assertJsonSuccess(
        postArrow("/v1/table/" + P2_DECLARED_TABLE_ID + "/create", arrowSmallStreamFixture()));
  }

  private void assertJsonSuccess(AggregatedHttpResponse response) {
    assertSuccess(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .as(response.contentUtf8())
        .contains("application/json");
  }

  private void assertArrowSuccess(AggregatedHttpResponse response) {
    assertSuccess(response);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .as(response.contentUtf8())
        .contains("application/vnd.apache.arrow");
  }
}
