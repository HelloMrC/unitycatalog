package io.unitycatalog.server.service.lance;

import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lance-phase2")
@Disabled("""
    Superseded by enabled tests:
    - P2-ARROW-001~006: LancePhase2ArrowRequestReaderRestTest
    - P2-ARROW-007~009: LancePhase2ArrowResponseWriterRestTest
    - P2-ARROW-010~011: LancePhase2ArrowRequestReaderRestTest
    - P2-ARROW-012 streaming limit: LancePhase2WorkerHttpBackendRestTest
    Retain for nightly pressure/real worker extension.""")
class LancePhase2ArrowRestTest extends BaseLancePhase2RestTest {

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
    assertThat(json(response).path("type").asText()).containsIgnoringCase("media");
  }

  @Test
  @DisplayName("P2-ARROW-005 oversized Arrow body returns 413")
  void oversizedArrowBodyReturns413() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowLargeStreamFixture());

    assertLanceErrorShape(response, 413);
  }

  @Test
  @DisplayName("P2-ARROW-006 large stream is not fully buffered in UC main process")
  void largeStreamIsNotFullyBufferedInUcMainProcess() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-test-streaming-probe", "true"));

    assertSuccess(response);
    assertThat(json(response).path("requestBufferedBytes").asLong()).isLessThan(1024 * 1024);
    assertThat(json(response).path("streamPassedThrough").asBoolean()).isTrue();
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
  @DisplayName("P2-ARROW-009 pre-streaming auth or resolver failures return JSON errors")
  void preStreamingAuthOrResolverFailuresReturnJsonErrors() throws Exception {
    AggregatedHttpResponse response =
        postQueryExpectingArrow("/v1/table/prod$team_a$missing/query", "{}");

    assertLanceErrorShape(response, 404);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("json");
  }

  @Test
  @DisplayName("P2-ARROW-010 schema peek is off by default")
  void schemaPeekIsOffByDefault() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow("/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert", arrowSmallStreamFixture());

    assertSuccess(response);
    assertThat(json(response).path("schemaSource").asText()).isIn("worker", "existing");
    assertThat(json(response).path("schemaPeeked").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("P2-ARROW-011 schema peek reads only schema message when enabled")
  void schemaPeekReadsOnlySchemaMessageWhenEnabled() throws Exception {
    createActiveTableFixture();

    AggregatedHttpResponse response =
        postArrow(
            "/v1/table/" + P2_ACTIVE_TABLE_ID + "/insert",
            arrowSmallStreamFixture(),
            Map.of("x-lance-test-arrow-peek-schema", "true"));

    assertSuccess(response);
    assertThat(json(response).path("schemaPeeked").asBoolean()).isTrue();
    assertThat(json(response).path("recordBatchesParsedByUc").asInt()).isEqualTo(0);
    assertThat(json(response).path("schemaSource").asText())
        .isIn("arrow-peek", "worker", "existing");
  }
}
