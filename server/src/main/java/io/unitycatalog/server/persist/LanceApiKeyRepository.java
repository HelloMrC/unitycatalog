package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceApiKeyDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

public class LanceApiKeyRepository {
  public static final String ACTIVE_STATUS = "ACTIVE";
  public static final String REVOKED_STATUS = "REVOKED";

  private final SessionFactory sessionFactory;

  public LanceApiKeyRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public LanceApiKeyDAO createApiKey(
      String plainTextKey,
      String principalId,
      String principalType,
      String status,
      String createdBy,
      Date expiresAt,
      Date revokedAt) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session ->
            createApiKey(
                session,
                plainTextKey,
                principalId,
                principalType,
                status,
                createdBy,
                expiresAt,
                revokedAt),
        "Failed to create Lance API key",
        false);
  }

  private LanceApiKeyDAO createApiKey(
      Session session,
      String plainTextKey,
      String principalId,
      String principalType,
      String status,
      String createdBy,
      Date expiresAt,
      Date revokedAt) {
    String keyHash = hashApiKey(plainTextKey);
    if (findByKeyHash(session, keyHash).isPresent()) {
      throw new BaseException(ErrorCode.ALREADY_EXISTS, "Lance API key already exists.");
    }

    Date now = new Date();
    LanceApiKeyDAO apiKeyDAO =
        LanceApiKeyDAO.builder()
            .id(UUID.randomUUID())
            .keyHash(keyHash)
            .principalId(principalId)
            .principalType(principalType)
            .status(status)
            .createdAt(now)
            .createdBy(createdBy)
            .updatedAt(now)
            .updatedBy(createdBy)
            .expiresAt(expiresAt)
            .revokedAt(revokedAt)
            .build();
    session.persist(apiKeyDAO);
    return apiKeyDAO;
  }

  public Optional<LanceApiKeyDAO> findByPlainTextKey(String plainTextKey) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> findByKeyHash(session, hashApiKey(plainTextKey)),
        "Failed to find Lance API key",
        true);
  }

  public void recordLastUsed(UUID apiKeyId) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          LanceApiKeyDAO apiKeyDAO = session.get(LanceApiKeyDAO.class, apiKeyId);
          if (apiKeyDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Lance API key not found: " + apiKeyId);
          }
          Date now = new Date();
          apiKeyDAO.setLastUsedAt(now);
          apiKeyDAO.setUpdatedAt(now);
          session.merge(apiKeyDAO);
          return null;
        },
        "Failed to update Lance API key last-used timestamp",
        false);
  }

  private Optional<LanceApiKeyDAO> findByKeyHash(Session session, String keyHash) {
    Query<LanceApiKeyDAO> query =
        session.createQuery("FROM LanceApiKeyDAO WHERE keyHash = :keyHash", LanceApiKeyDAO.class);
    query.setParameter("keyHash", keyHash);
    query.setMaxResults(1);
    return query.uniqueResultOptional();
  }

  public static String hashApiKey(String plainTextKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(plainTextKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new BaseException(ErrorCode.INTERNAL, "SHA-256 is not available.");
    }
  }
}
