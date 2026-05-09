package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpStatus;

public class LanceProtocolException extends RuntimeException {
  private final HttpStatus status;
  private final String type;

  LanceProtocolException(HttpStatus status, String type, String message) {
    super(message);
    this.status = status;
    this.type = type;
  }

  HttpStatus status() {
    return status;
  }

  String type() {
    return type;
  }
}
