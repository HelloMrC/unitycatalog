package io.unitycatalog.server.service.lance;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class LanceIdentifierCodec {
  private static final String DEFAULT_DELIMITER = "$";

  public List<String> decodeIdentifier(String identifier, String delimiter) {
    String effectiveDelimiter = normalizeDelimiter(delimiter);
    if (identifier == null || identifier.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Identifier must not be empty");
    }
    return Arrays.stream(identifier.split(Pattern.quote(effectiveDelimiter), -1))
        .map(this::decodeSegment)
        .peek(
            segment -> {
              if (segment.isEmpty()) {
                throw new BaseException(
                    ErrorCode.INVALID_ARGUMENT, "Identifier contains an empty segment");
              }
            })
        .toList();
  }

  public String toPathKey(List<String> segments) {
    if (segments.isEmpty()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Identifier must not be empty");
    }
    return segments.stream().map(segment -> encodeSegment(segment, "/")).reduce((a, b) -> a + "/" + b)
        .orElseThrow();
  }

  public String toExternalIdentifier(String pathKey, String delimiter) {
    String effectiveDelimiter = normalizeDelimiter(delimiter);
    return Arrays.stream(pathKey.split("/", -1))
        .map(this::decodeSegment)
        .map(segment -> encodeSegment(segment, effectiveDelimiter))
        .reduce((a, b) -> a + effectiveDelimiter + b)
        .orElseThrow();
  }

  public String normalizeDelimiter(String delimiter) {
    return delimiter == null || delimiter.isBlank() ? DEFAULT_DELIMITER : delimiter;
  }

  private String decodeSegment(String segment) {
    return URLDecoder.decode(segment, StandardCharsets.UTF_8);
  }

  private String encodeSegment(String segment, String delimiter) {
    String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    if (".".equals(delimiter)) {
      encoded = encoded.replace(".", "%2E");
    }
    return encoded;
  }
}
