package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import java.util.LinkedHashMap;
import java.util.Map;

class LanceArrowRequestReader {
  static final String ARROW_BODY_ATTRIBUTE = "__arrowBody";
  private static final String SCHEMA_PEEK_HEADER = "x-lance-test-arrow-peek-schema";

  Map<String, Object> read(AggregatedHttpRequest request) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    boolean schemaPeeked = Boolean.parseBoolean(request.headers().get(SCHEMA_PEEK_HEADER));
    byte[] arrowBody = request.content().array();
    // Aggregated mode is for local/test backends; UC records metadata about the Arrow payload but
    // does not parse record batches or become a Lance execution engine.
    attributes.put("inputData", "arrow-stream");
    attributes.put("inputBytes", arrowBody.length);
    attributes.put("inputMediaType", request.contentType().withoutParameters().toString());
    attributes.put("requestBufferedBytes", arrowBody.length);
    attributes.put("streamPassedThrough", false);
    attributes.put("ucRequestMode", "aggregated");
    attributes.put("schemaPeeked", schemaPeeked);
    attributes.put("recordBatchesParsedByUc", 0);
    attributes.put("schemaSource", schemaPeeked ? "arrow-peek" : "worker");
    attributes.put(ARROW_BODY_ATTRIBUTE, arrowBody);
    return attributes;
  }

  Map<String, Object> readStreaming(RequestHeaders headers) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    Long contentLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH);
    // Streaming mode intentionally omits __arrowBody; the HttpRequest itself is forwarded to the
    // worker so large writes do not require buffering in UC.
    attributes.put("inputData", "arrow-stream");
    attributes.put("inputBytes", contentLength == null ? 0L : contentLength);
    attributes.put("inputMediaType", contentType(headers).withoutParameters().toString());
    attributes.put("requestBufferedBytes", 0L);
    attributes.put("requestContentLength", contentLength == null ? -1L : contentLength);
    attributes.put("streamPassedThrough", true);
    attributes.put("ucRequestMode", "streaming");
    attributes.put("schemaPeeked", false);
    attributes.put("recordBatchesParsedByUc", 0);
    attributes.put("schemaSource", "worker");
    return attributes;
  }

  private MediaType contentType(RequestHeaders headers) {
    String value = headers.get(HttpHeaderNames.CONTENT_TYPE);
    return value == null ? MediaType.OCTET_STREAM : MediaType.parse(value);
  }
}
