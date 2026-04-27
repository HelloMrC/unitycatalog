package io.unitycatalog.server.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.persist.dao.LanceApiKeyDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Date;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LanceApiKeyRepositoryTest {
  private static final String PLAIN_TEXT_KEY = "phase1-valid-api-key";

  private LanceApiKeyRepository repository;

  @BeforeEach
  void setUp() {
    Properties properties = new Properties();
    properties.setProperty(Property.SERVER_ENV.getKey(), "test");

    ServerProperties serverProperties = new ServerProperties(properties);
    HibernateConfigurator hibernateConfigurator = new HibernateConfigurator(serverProperties);
    Repositories repositories =
        new Repositories(hibernateConfigurator.getSessionFactory(), serverProperties);

    repository = repositories.getLanceApiKeyRepository();
  }

  @Test
  void createApiKeyStoresOnlyHashAndPrincipalMetadata() {
    LanceApiKeyDAO apiKey =
        repository.createApiKey(
            PLAIN_TEXT_KEY,
            "phase1-service-principal",
            "SERVICE_PRINCIPAL",
            LanceApiKeyRepository.ACTIVE_STATUS,
            "phase1-test",
            null,
            null);

    assertThat(apiKey.getKeyHash()).isNotEqualTo(PLAIN_TEXT_KEY);
    assertThat(apiKey.getKeyHash()).isEqualTo(LanceApiKeyRepository.hashApiKey(PLAIN_TEXT_KEY));
    assertThat(apiKey.toString()).doesNotContain(PLAIN_TEXT_KEY);

    LanceApiKeyDAO resolved = repository.findByPlainTextKey(PLAIN_TEXT_KEY).orElseThrow();
    assertThat(resolved.getPrincipalId()).isEqualTo("phase1-service-principal");
    assertThat(resolved.getPrincipalType()).isEqualTo("SERVICE_PRINCIPAL");
    assertThat(resolved.getStatus()).isEqualTo(LanceApiKeyRepository.ACTIVE_STATUS);
  }

  @Test
  void createApiKeyRejectsDuplicatePlainTextKey() {
    repository.createApiKey(
        PLAIN_TEXT_KEY,
        "phase1-service-principal",
        "SERVICE_PRINCIPAL",
        LanceApiKeyRepository.ACTIVE_STATUS,
        "phase1-test",
        null,
        null);

    BaseException exception =
        assertThrows(
            BaseException.class,
            () ->
                repository.createApiKey(
                    PLAIN_TEXT_KEY,
                    "phase1-service-principal",
                    "SERVICE_PRINCIPAL",
                    LanceApiKeyRepository.ACTIVE_STATUS,
                    "phase1-test",
                    null,
                    null));

    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
  }

  @Test
  void recordLastUsedUpdatesAuditTimestamp() {
    LanceApiKeyDAO apiKey =
        repository.createApiKey(
            "phase1-last-used-api-key",
            "phase1-service-principal",
            "SERVICE_PRINCIPAL",
            LanceApiKeyRepository.ACTIVE_STATUS,
            "phase1-test",
            null,
            null);

    repository.recordLastUsed(apiKey.getId());

    LanceApiKeyDAO resolved =
        repository.findByPlainTextKey("phase1-last-used-api-key").orElseThrow();
    assertThat(resolved.getLastUsedAt())
        .isAfterOrEqualTo(new Date(apiKey.getCreatedAt().getTime()));
  }
}
