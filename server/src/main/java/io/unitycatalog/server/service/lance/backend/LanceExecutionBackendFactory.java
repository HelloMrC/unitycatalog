package io.unitycatalog.server.service.lance.backend;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public final class LanceExecutionBackendFactory {
  private LanceExecutionBackendFactory() {}

  public static LanceAdvancedExecutionBackend create(ServerProperties serverProperties) {
    String backendType = serverProperties.get(Property.LANCE_EXECUTION_BACKEND_TYPE);
    if ("worker-http".equalsIgnoreCase(backendType)) {
      return new WorkerHttpLanceExecutionBackend(serverProperties);
    }
    if ("disabled".equalsIgnoreCase(backendType)) {
      return new DisabledLanceExecutionBackend();
    }
    String backendClassName = serverProperties.get(Property.LANCE_EXECUTION_BACKEND_CLASS);
    if (backendClassName != null && !backendClassName.isBlank()) {
      return createConfiguredBackend(backendClassName.trim(), serverProperties);
    }
    return new DisabledLanceExecutionBackend();
  }

  private static LanceAdvancedExecutionBackend createConfiguredBackend(
      String backendClassName, ServerProperties serverProperties) {
    try {
      Class<?> backendClass = Class.forName(backendClassName);
      if (!LanceAdvancedExecutionBackend.class.isAssignableFrom(backendClass)) {
        throw new BaseException(
            ErrorCode.INVALID_ARGUMENT,
            "Configured Lance execution backend does not implement LanceAdvancedExecutionBackend: "
                + backendClassName);
      }
      return instantiate(backendClass, serverProperties);
    } catch (ClassNotFoundException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "Configured Lance execution backend class not found: " + backendClassName,
          e);
    } catch (NoSuchMethodException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "Configured Lance execution backend must expose a no-argument or ServerProperties constructor: "
              + backendClassName,
          e);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new BaseException(
          ErrorCode.INTERNAL,
          "Failed to initialize configured Lance execution backend: " + backendClassName,
          e);
    }
  }

  private static LanceAdvancedExecutionBackend instantiate(
      Class<?> backendClass, ServerProperties serverProperties)
      throws NoSuchMethodException, InvocationTargetException, InstantiationException,
          IllegalAccessException {
    try {
      Constructor<?> constructor = backendClass.getDeclaredConstructor(ServerProperties.class);
      constructor.setAccessible(true);
      return (LanceAdvancedExecutionBackend) constructor.newInstance(serverProperties);
    } catch (NoSuchMethodException ignored) {
      Constructor<?> constructor = backendClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      return (LanceAdvancedExecutionBackend) constructor.newInstance();
    }
  }
}
