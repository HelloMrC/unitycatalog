package io.unitycatalog.server.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.unitycatalog.control.model.User;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.security.JwtClaim;
import io.unitycatalog.server.security.SecurityContext;
import io.unitycatalog.server.utils.JwksOperations;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenExchangeService {
  private static final Logger LOGGER = LoggerFactory.getLogger(TokenExchangeService.class);

  private final SecurityContext securityContext;
  private final JwksOperations jwksOperations;
  private final ServerProperties serverProperties;
  private final UserRepository userRepository;

  public TokenExchangeService(
      SecurityContext securityContext,
      ServerProperties serverProperties,
      Repositories repositories) {
    this.securityContext = securityContext;
    this.jwksOperations = new JwksOperations(securityContext);
    this.serverProperties = serverProperties;
    this.userRepository = repositories.getUserRepository();
  }

  public TokenExchangeResult exchangeToken(String subjectToken) {
    DecodedJWT decodedJWT;
    try {
      decodedJWT = JWT.decode(subjectToken);
    } catch (JWTDecodeException e) {
      LOGGER.debug("Token rejected: malformed token", e);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Invalid token: " + e.getMessage(), e);
    }

    List<String> allowedIssuers = serverProperties.getAllowedIssuers();
    if (allowedIssuers.isEmpty()) {
      LOGGER.error("No allowed issuers configured");
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "No allowed issuers configured. Set server.allowed-issuers in server.properties");
    }

    List<String> audiences = serverProperties.getAudiences();
    if (audiences.isEmpty()) {
      LOGGER.error("No audiences configured");
      throw new OAuthInvalidRequestException(
          ErrorCode.INVALID_ARGUMENT,
          "No audiences configured. Set server.audiences in server.properties");
    }

    String issuer = decodedJWT.getIssuer();
    if (!allowedIssuers.contains(issuer)) {
      LOGGER.debug("Token rejected: invalid issuer '{}'", issuer);
      throw new OAuthInvalidRequestException(ErrorCode.UNAUTHENTICATED, "Invalid issuer");
    }

    String keyId = decodedJWT.getKeyId();
    String alg = decodedJWT.getAlgorithm();

    LOGGER.debug("Validating token for issuer: {} and keyId: {}", issuer, keyId);
    try {
      JWTVerifier jwtVerifier =
          jwksOperations.verifierForIssuerAndKey(issuer, keyId, alg, audiences);
      decodedJWT = jwtVerifier.verify(decodedJWT);
    } catch (JWTVerificationException e) {
      LOGGER.debug("Token rejected: verification failed", e);
      throw new OAuthInvalidRequestException(
          ErrorCode.UNAUTHENTICATED, "Token verification failed: " + e.getMessage(), e);
    }

    verifyPrincipal(decodedJWT);

    String accessToken = securityContext.createAccessToken(decodedJWT);
    String principal = principalFromToken(JWT.decode(accessToken));
    return new TokenExchangeResult(accessToken, principal);
  }

  public String principalFromToken(DecodedJWT decodedJWT) {
    return decodedJWT
        .getClaims()
        .getOrDefault(JwtClaim.EMAIL.key(), decodedJWT.getClaim(JwtClaim.SUBJECT.key()))
        .asString();
  }

  private void verifyPrincipal(DecodedJWT decodedJWT) {
    String subject = principalFromToken(decodedJWT);

    LOGGER.debug("Validating principal: {}", subject);

    if ("admin".equals(subject)) {
      LOGGER.debug("admin always allowed");
      return;
    }

    try {
      User user = userRepository.getUserByEmail(subject);
      if (user != null && user.getState() == User.StateEnum.ENABLED) {
        LOGGER.debug("Principal {} is enabled", subject);
        return;
      }
    } catch (Exception e) {
      // Ignore lookup failures and return the same stable error below.
    }

    throw new OAuthInvalidRequestException(
        ErrorCode.INVALID_ARGUMENT, "User not allowed: " + subject);
  }

  public record TokenExchangeResult(String accessToken, String principal) {}
}
