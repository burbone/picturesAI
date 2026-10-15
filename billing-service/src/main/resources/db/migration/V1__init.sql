CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    plan VARCHAR(20) NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE quota_reservations (
    generation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    reserved_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMP,
    released_at TIMESTAMP
);

CREATE INDEX idx_quota_reservations_user_date
    ON quota_reservations (user_id, reserved_date);

CREATE INDEX idx_quota_reservations_status_expires
    ON quota_reservations (status, expires_at)
    WHERE status = 'RESERVED';

CREATE TABLE daily_usage (
    user_id UUID NOT NULL,
    usage_date DATE NOT NULL,
    used_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID,
    event_type VARCHAR(50),
    payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;