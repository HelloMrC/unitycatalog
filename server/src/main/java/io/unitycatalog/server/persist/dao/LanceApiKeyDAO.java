package io.unitycatalog.server.persist.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Date;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "uc_lance_api_keys",
    indexes = {
      @Index(name = "idx_lance_api_key_principal", columnList = "principal_id,principal_type"),
      @Index(name = "idx_lance_api_key_status", columnList = "status")
    },
    uniqueConstraints = {@UniqueConstraint(columnNames = {"key_hash"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LanceApiKeyDAO {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "key_hash", nullable = false, length = 128)
  private String keyHash;

  @Column(name = "principal_id", nullable = false)
  private String principalId;

  @Column(name = "principal_type", nullable = false)
  private String principalType;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_at", nullable = false)
  private Date updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;

  @Column(name = "expires_at")
  private Date expiresAt;

  @Column(name = "revoked_at")
  private Date revokedAt;

  @Column(name = "last_used_at")
  private Date lastUsedAt;
}
