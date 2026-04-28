package io.unitycatalog.server.service.lance.backend;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public final class LanceExecutionBackendFactory {
  private LanceExecutionBackendFactory() {}

  public static LanceExecutionBackend create(ServerProperties serverProperties) {
    String backendClassName = serverProperties.get(Property.LANCE_EXECUTION_BACKEND_CLASS);
    if (backendClassName != null && !backendClassName.isBlank()) {
      return createConfiguredBackend(backendClassName.trim());
    }
    return new DisabledLanceExecutionBackend();
  }

  private static LanceExecutionBackend createConfiguredBackend(String backendClassName) {
    try {
      Class<?> backendClass = Class.forName(backendClassName);
      if (!LanceExecutionBackend.class.isAssignableFrom(backendClass)) {
        throw new BaseException(
            ErrorCode.INVALID_ARGUMENT,
            "Configured Lance execution backend does not implement LanceExecutionBackend: "
                + backendClassName);
      }
      Constructor<?> constructor = backendClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      return (LanceExecutionBackend) constructor.newInstance();
    } catch (ClassNotFoundException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "Configured Lance execution backend class not found: " + backendClassName,
          e);
    } catch (NoSuchMethodException e) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "Configured Lance execution backend must expose a no-argument constructor: "
              + backendClassName,
          e);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new BaseException(
          ErrorCode.INTERNAL,
          "Failed to initialize configured Lance execution backend: " + backendClassName,
          e);
    }
  }
}
