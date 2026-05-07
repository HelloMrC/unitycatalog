package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import io.unitycatalog.server.service.lance.backend.LanceExecutionContext;
import io.unitycatalog.server.service.lance.backend.LanceExecutionResult;
import java.nio.charset.StandardCharsets;

class LanceArrowResponseWriter {
  static final MediaType ARROW_FILE = MediaType.parse("application/vnd.apache.arrow.file");
  static final MediaType ARROW_STREAM = MediaType.parse("application/vnd.apache.arrow.stream");

  boolean acceptsArrow(AggregatedHttpRequest request) {
    String accept = request.headers().get(HttpHeaderNames.ACCEPT);
    return accept == null
        || accept.isBlank()
        || accept.contains("*/*")
        || accept.contains(ARROW_FILE.toString())
        || accept.contains(ARROW_STREAM.toString());
  }

  HttpResponse write(
      AggregatedHttpRequest request, LanceExecutionResult result, LanceExecutionContext context) {
    MediaType responseType = responseType(request);
    ResponseHeaders headers =
        ResponseHeaders.builder(HttpStatus.OK)
            .contentType(responseType)
            .add("x-request-id", context.requestId())
            .build();
    return HttpResponse.of(headers, HttpData.wrap(arrowBytes(responseType, result)));
  }

  private MediaType responseType(AggregatedHttpRequest request) {
    String accept = request.headers().get(HttpHeaderNames.ACCEPT);
    if (accept != null && accept.contains(ARROW_STREAM.toString())) {
      return ARROW_STREAM;
    }
    return ARROW_FILE;
  }

  private byte[] arrowBytes(MediaType responseType, LanceExecutionResult result) {
    Object arrow = result.payload().getOrDefault("arrow", "empty");
    String payload = "ARROW1\n" + responseType + "\n" + arrow + "\nARROW1";
    return payload.getBytes(StandardCharsets.UTF_8);
  }
}
