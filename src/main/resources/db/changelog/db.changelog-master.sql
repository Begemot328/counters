--liquibase formatted sql

-- The schema itself is created by the application before Liquibase runs (Liquibase needs an
-- existing default schema for its tracking tables). Table names are unqualified on purpose:
-- they resolve through the connection's search_path (currentSchema), so the schema name is
-- configured in one place — application.conf.

--changeset counters:001-create-counters-table
CREATE TABLE counters (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    value      INTEGER      NOT NULL DEFAULT 0,
    is_deleted BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Only live counters compete for a name: a soft-deleted name can be created again while
-- duplicate live names still fail. The predicate must match ON CONFLICT in the repository.
CREATE UNIQUE INDEX uq_counters_name_active ON counters (name) WHERE NOT is_deleted;
--rollback DROP TABLE counters;

--changeset counters:002-create-idempotency-keys-table
CREATE TABLE idempotency_keys (
    key           UUID        PRIMARY KEY,
    request_hash  TEXT        NOT NULL,
    response_body JSONB,
    response_code INTEGER,
    state         VARCHAR(16) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE idempotency_keys;
