# LanceDB Adaptation Notes

This directory archives the main references and design notes for making Unity Catalog (UC) act as the metadata control plane for LanceDB / Lance tables.

## Goal

- Use UC to manage Lance table metadata, identity, permissions, and storage locations.
- Keep the design aligned with upstream Lance Namespace and LanceDB evolution.
- Avoid overfitting the integration to Delta UniForm or Iceberg-only assumptions.

## Current conclusion

UC should not model Lance support as "another Iceberg-like projection".

In this repository, Iceberg support is exposed through a dedicated Iceberg REST service over Delta UniForm metadata, while Lance upstream already defines:

- a Unity Catalog integration model for Lance tables
- a broader Lance Namespace contract
- an optional REST data plane for query, indexing, versioning, and tags

For that reason, the most future-proof direction is:

1. Treat Lance Namespace compatibility as the target contract.
2. Use UC catalogs, schemas, tables, permissions, and temporary credentials as the metadata backend.
3. Add Lance-specific endpoints only where the Lance Namespace or Lance REST model needs them.

## Recommended rollout

| Phase | Focus | Outcome |
| --- | --- | --- |
| 1 | Metadata registration | Register Lance tables in UC as `EXTERNAL` tables with Lance-specific properties. |
| 2 | Unity Namespace compatibility | Implement the Lance Unity Namespace mapping on top of UC REST and persistence. |
| 3 | Selective data plane | Add Lance REST endpoints for operations that cannot be satisfied by UC metadata APIs alone. |
| 4 | Managed Lance tables | Revisit only if UC later needs to own Lance lifecycle, staging, and commit semantics. |

## Why this is not just a volume feature

UC documentation already mentions Lance datasets as a good fit for volumes. That is useful for file-oriented access, but it is not sufficient if the product goal is governed table metadata, schema visibility, namespace operations, and long-term compatibility with Lance clients.

If the desired abstraction is "Lance tables governed by UC", tables plus Namespace compatibility are the stronger foundation.

## Contents

- [Source Archive](source_archive.md)
- [API Compatibility](api_compatibility.md)
- [Architecture Direction](architecture_direction.md)
