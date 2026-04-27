package io.unitycatalog.server.service.lance;

import static io.unitycatalog.server.security.SecurityContext.Issuers.INTERNAL;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.LanceApiKeyRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.LanceApiKeyDAO;
import io.unitycatalog.server.security.SecurityContext;
import io.unitycatalog.server.service.TokenExchangeService;
import io.unitycatalog.server.utils.JwksOperations;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LanceAuthDecorator implements DecoratingHttpServiceFunction {
  private static final String X_API_KEY = "x-api-key";
  private static final String BEARER_PREFIX = "Bearer ";

  private final LanceApiKeyRepository apiKeyRepository;
  private final JwksOperations jwksOperations;
  private final TokenExchangeService tokenExchangeService;

  public LanceAuthDecorator(
      SecurityContext securityContext,
      ServerProperties serverProperties,
      Repositories repositories) {
    this.apiKeyRepository = repositories.getLanceApiKeyRepository();
    this.jwksOperations = new JwksOperations(securityContext);
    this.tokenExchangeService =
        new TokenExchangeService(securityContext, serverProperties, repositories);
  }

  @Override
  public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
      throws Exception {
    Map<String, String> contextHeaders = extractContextHeaders(req);
    if (!contextHeaders.isEmpty()) {
      ctx.setAttr(LanceRequestContext.CONTEXT_HEADERS_ATTR, contextHeaders);
    }
    Optional<String> bearerToken = bearerToken(req);
    String apiKey = req.headers().get(X_API_KEY);
    boolean hasApiKey = apiKey != null && !apiKey.isBlank();
    if (bearerToken.isPresent() && hasApiKey) {
      return unauthenticated(
          "Authentication methods are mutually exclusive: provide either Authorization Bearer or"
              + " x-api-key.");
    }

    String bearerPrincipal;
    try {
      bearerPrincipal = bearerToken.map(this::principalFromBearer).orElse(null);
    } catch (BaseException e) {
      return lanceError(e.getErrorCode(), e.getErrorMessage());
    }
    if (bearerPrincipal != null) {
      ctx.setAttr(LanceRequestContext.PRINCIPAL_ATTR, bearerPrincipal);
    }

    if (!hasApiKey) {
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

  private Optional<String> bearerToken(HttpRequest req) {
    String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }

    String accessToken = authorization.substring(BEARER_PREFIX.length()).trim();
    return accessToken.isEmpty() ? Optional.empty() : Optional.of(accessToken);
  }

  private String principalFromBearer(String accessToken) {
    DecodedJWT decodedJWT;
    try {
      decodedJWT = JWT.decode(accessToken);
    } catch (JWTDecodeException e) {
      throw new BaseException(ErrorCode.UNAUTHENTICATED, "Invalid Bearer token.", e);
    }
    if (!INTERNAL.equals(decodedJWT.getIssuer())) {
      return tokenExchangeService.exchangeToken(accessToken).principal();
    }

    try {
      JWTVerifier jwtVerifier =
          jwksOperations.verifierForIssuerAndKey(
              decodedJWT.getIssuer(), decodedJWT.getKeyId(), decodedJWT.getAlgorithm(), List.of());
      DecodedJWT verifiedJWT = jwtVerifier.verify(decodedJWT);
      String email = verifiedJWT.getClaim("email").asString();
      return isBlank(email) ? verifiedJWT.getSubject() : email;
    } catch (JWTVerificationException e) {
      throw new BaseException(ErrorCode.UNAUTHENTICATED, "Invalid Bearer token.", e);
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
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
    return lanceError(ErrorCode.UNAUTHENTICATED, message);
  }

  private HttpResponse lanceError(ErrorCode errorCode, String message) {
    return HttpResponse.ofJson(
        errorCode.getHttpStatus(),
        Map.of(
            "type", errorCode.name().toLowerCase(),
            "message", message,
            "code", errorCode.getHttpStatus().code()));
  }
}
