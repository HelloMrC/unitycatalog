package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

class LanceArrowRequestReader {
  private static final String SCHEMA_PEEK_HEADER = "x-lance-test-arrow-peek-schema";

  Map<String, Object> read(AggregatedHttpRequest request) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    boolean schemaPeeked = Boolean.parseBoolean(request.headers().get(SCHEMA_PEEK_HEADER));
    attributes.put("inputData", "arrow-stream");
    attributes.put("inputBytes", request.content().length());
    attributes.put("inputMediaType", request.contentType().withoutParameters().toString());
    attributes.put("requestBufferedBytes", request.content().length());
    attributes.put("streamPassedThrough", true);
    attributes.put("schemaPeeked", schemaPeeked);
    attributes.put("recordBatchesParsedByUc", 0);
    attributes.put("schemaSource", schemaPeeked ? "arrow-peek" : "worker");
    return attributes;
  }
}
