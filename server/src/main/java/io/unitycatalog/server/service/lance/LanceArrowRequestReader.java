package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

class LanceArrowRequestReader {
  static final String ARROW_BODY_ATTRIBUTE = "__arrowBody";
  private static final String SCHEMA_PEEK_HEADER = "x-lance-test-arrow-peek-schema";

  Map<String, Object> read(AggregatedHttpRequest request) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    boolean schemaPeeked = Boolean.parseBoolean(request.headers().get(SCHEMA_PEEK_HEADER));
    byte[] arrowBody = request.content().array();
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
}
