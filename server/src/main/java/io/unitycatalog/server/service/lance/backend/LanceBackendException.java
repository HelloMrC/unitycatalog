package io.unitycatalog.server.service.lance.backend;

import com.linecorp.armeria.common.HttpStatus;

public class LanceBackendException extends RuntimeException {
  private final HttpStatus status;
  private final String type;
  private final String backendRequestId;
  private final String backendType;

  public LanceBackendException(
      HttpStatus status, String type, String backendRequestId, String message) {
    this(status, type, backendRequestId, message, null, null);
  }

  public LanceBackendException(
      HttpStatus status, String type, String backendRequestId, String message, Throwable cause) {
    this(status, type, backendRequestId, message, null, cause);
  }

  public LanceBackendException(
      HttpStatus status, String type, String backendRequestId, String message, String backendType) {
    this(status, type, backendRequestId, message, backendType, null);
  }

  public LanceBackendException(
      HttpStatus status,
      String type,
      String backendRequestId,
      String message,
      String backendType,
      Throwable cause) {
    super(message, cause);
    this.status = status;
    this.type = type;
    this.backendRequestId = backendRequestId;
    this.backendType = backendType;
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

  public String backendType() {
    return backendType;
  }
}
