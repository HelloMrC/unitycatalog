package io.unitycatalog.server.service.lance;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceApiKeyRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceApiKeyDAO;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class LanceAuthDecorator implements DecoratingHttpServiceFunction {
  private static final String X_API_KEY = "x-api-key";

  private final LanceApiKeyRepository apiKeyRepository;

  public LanceAuthDecorator(Repositories repositories) {
    this.apiKeyRepository = repositories.getLanceApiKeyRepository();
  }

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
      throws Exception {
    Map<String, String> contextHeaders = extractContextHeaders(req);
    if (!contextHeaders.isEmpty()) {
      ctx.setAttr(LanceRequestContext.CONTEXT_HEADERS_ATTR, contextHeaders);
    }

    String apiKey = req.headers().get(X_API_KEY);
    if (apiKey == null || apiKey.isBlank()) {
      return delegate.serve(ctx, req);
    }

    Optional<LanceApiKeyDAO> apiKeyDAO = apiKeyRepository.findByPlainTextKey(apiKey);
    if (apiKeyDAO.isEmpty()) {
      return unauthenticated("Invalid API key.");
    }
    LanceApiKeyDAO resolved = apiKeyDAO.get();
    if (isRevoked(resolved)) {
      return unauthenticated("API key has been revoked.");
    }
    if (isExpired(resolved)) {
      return unauthenticated("API key has expired.");
    }

    apiKeyRepository.recordLastUsed(resolved.getId());
    ctx.setAttr(LanceRequestContext.PRINCIPAL_ATTR, resolved.getPrincipalId());
    return delegate.serve(ctx, req);
  }

  private Map<String, String> extractContextHeaders(HttpRequest req) {
    Map<String, String> contextHeaders = new LinkedHashMap<>();
    req.headers()
        .forEach(
            header -> {
              String name = header.getKey().toString();
              if (name.startsWith("x-lance-")) {
                contextHeaders.put(name, header.getValue());
              }
            });
    return contextHeaders;
  }

  private boolean isRevoked(LanceApiKeyDAO apiKeyDAO) {
    return LanceApiKeyRepository.REVOKED_STATUS.equalsIgnoreCase(apiKeyDAO.getStatus())
        || apiKeyDAO.getRevokedAt() != null;
  }

  private boolean isExpired(LanceApiKeyDAO apiKeyDAO) {
    return LanceApiKeyRepository.EXPIRED_STATUS.equalsIgnoreCase(apiKeyDAO.getStatus())
        || (apiKeyDAO.getExpiresAt() != null && apiKeyDAO.getExpiresAt().before(new Date()));
  }

  private HttpResponse unauthenticated(String message) {
    ErrorCode errorCode = ErrorCode.UNAUTHENTICATED;
    return HttpResponse.ofJson(
        errorCode.getHttpStatus(),
        Map.of(
            "type", errorCode.name().toLowerCase(),
            "message", message,
            "code", errorCode.getHttpStatus().code()));
  }
}
