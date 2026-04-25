package io.unitycatalog.server.persist.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Date;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
    name = "uc_lance_namespaces",
    indexes = {
      @Index(name = "idx_lance_namespace_parent", columnList = "root_scope_id,parent_namespace_id"),
      @Index(name = "idx_lance_namespace_path_key", columnList = "root_scope_id,path_key")
    },
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"root_scope_id", "parent_namespace_id", "name"}),
      @UniqueConstraint(columnNames = {"root_scope_id", "path_key"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public class LanceNamespaceDAO extends IdentifiableDAO {

  @Column(name = "root_scope_id", nullable = false)
  private UUID rootScopeId;

  @Column(name = "parent_namespace_id")
  private UUID parentNamespaceId;

  @Column(name = "depth", nullable = false)
  private Integer depth;

  @Column(name = "path_key", nullable = false, length = 2048)
  private String pathKey;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "owner")
  private String owner;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_at", nullable = false)
  private Date updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;
}
