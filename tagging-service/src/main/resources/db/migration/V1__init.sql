CREATE TABLE tag_vocabulary (
    id UUID PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    category VARCHAR(30) NOT NULL,
    aliases TEXT[] NOT NULL DEFAULT '{}',
    is_active BOOL NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE generation_tags (
    generation_id UUID NOT NULL,
    tag_id UUID NOT NULL REFERENCES tag_vocabulary(id),
    weight NUMERIC(3,2) NOT NULL CHECK (weight >= 0 AND weight <= 1),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (generation_id, tag_id)
);

CREATE TABLE tag_stats (
    tag_id UUID PRIMARY KEY REFERENCES tag_vocabulary(id),
    usage_count BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    published_at TIMESTAMP
);

CREATE INDEX idx_generation_tags_tag_id ON generation_tags(tag_id);
CREATE INDEX idx_generation_tags_tag_weight ON generation_tags(tag_id, weight DESC);
CREATE INDEX idx_tag_vocabulary_aliases ON tag_vocabulary USING GIN(aliases);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;