package io.unitycatalog.server.service.lance.backend;

import com.linecorp.armeria.common.HttpStatus;

public class LanceBackendException extends RuntimeException {
  private final HttpStatus status;
  private final String type;
  private final String backendRequestId;

  public LanceBackendException(
      HttpStatus status, String type, String backendRequestId, String message) {
    super(message);
    this.status = status;
    this.type = type;
    this.backendRequestId = backendRequestId;
  }

  public HttpStatus status() {
    return status;
  }

  public String type() {
    return type;
  }

  public String backendRequestId() {
    return backendRequestId;
  }
}
