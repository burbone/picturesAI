CREATE TABLE generations (
    id UUID PRIMARY KEY,
    user_id UUID,
    prompt TEXT NOT NULL,
    negative_prompt TEXT,
    model VARCHAR(50) NOT NULL,
    size VARCHAR(20) NOT NULL,
    tier VARCHAR(10) NOT NULL DEFAULT 'FREE',
    status VARCHAR(20) NOT NULL,
    image_url TEXT,
    thumbnail_url TEXT,
    provider VARCHAR(30),
    provider_job_id VARCHAR(100),
    error_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    is_public BOOLEAN NOT NULL DEFAULT false,
    idempotency_key VARCHAR(100),
    batch_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ
);

CREATE INDEX idx_generations_user_created
    ON generations (user_id, created_at DESC);

CREATE INDEX idx_generations_queue
    ON generations (tier, status, created_at)
    WHERE status = 'QUEUED';

CREATE UNIQUE INDEX idx_generations_idempotency
    ON generations (user_id, idempotency_key, created_at)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_generations_batch
    ON generations (batch_id)
    WHERE batch_id IS NOT NULL;

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;