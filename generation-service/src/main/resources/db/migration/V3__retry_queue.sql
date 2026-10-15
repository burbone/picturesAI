CREATE TABLE retry_queue (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL,
    user_id UUID,
    tier VARCHAR(10) NOT NULL,
    retry_count INT NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retry_queue_pending
    ON retry_queue (scheduled_at)
    WHERE processed_at IS NULL;