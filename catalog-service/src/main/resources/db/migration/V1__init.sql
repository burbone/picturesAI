CREATE TABLE catalog_images (
    id UUID PRIMARY KEY,
    user_id UUID,
    prompt TEXT NOT NULL,
    originals_bucket VARCHAR(255) NOT NULL,
    originals_key VARCHAR(512) NOT NULL,
    thumbnails_bucket VARCHAR(255) NOT NULL,
    thumbnails_key VARCHAR(512) NOT NULL,
    provider VARCHAR(100),
    completed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_catalog_images_feed
    ON catalog_images (completed_at DESC, id DESC);

CREATE INDEX idx_catalog_images_user
    ON catalog_images (user_id, completed_at DESC, id DESC);

CREATE INDEX idx_catalog_images_prompt_fts
    ON catalog_images USING GIN (to_tsvector('simple', prompt));

CREATE TABLE catalog_image_tags (
    image_id UUID NOT NULL REFERENCES catalog_images (id) ON DELETE CASCADE,
    tag VARCHAR(100) NOT NULL,
    PRIMARY KEY (image_id, tag)
);

CREATE INDEX idx_catalog_image_tags_tag
    ON catalog_image_tags (tag, image_id);

CREATE TABLE catalog_users (
    id UUID PRIMARY KEY,
    keycloak_id UUID,
    username VARCHAR(50),
    display_name VARCHAR(100),
    avatar_url TEXT,
    anonymized BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_catalog_users_keycloak_id
    ON catalog_users (keycloak_id)
    WHERE keycloak_id IS NOT NULL;

CREATE TABLE processed_events (
    event_id VARCHAR(100) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_events_processed_at
    ON processed_events (processed_at);