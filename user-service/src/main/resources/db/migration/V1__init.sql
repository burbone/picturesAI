CREATE TABLE users (
    id UUID PRIMARY KEY,
    keycloak_id UUID NOT NULL UNIQUE,
    username VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    avatar_url TEXT,
    bio TEXT,
    is_public BOOLEAN NOT NULL DEFAULT true,
    plan VARCHAR(20) NOT NULL DEFAULT 'FREE',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished_created_at
    ON outbox (created_at)
    WHERE published_at IS NULL;