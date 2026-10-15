#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-3}"
REPLICATION="${KAFKA_TOPIC_REPLICATION:-1}"

TOPICS=(
  generation.requested.pro
  generation.requested.free
  generation.requested.retry
  generation.requested.dlq
  generation.completed
  generation.failed
  generation.cancelled
  tags.extracted
  user.created
  user.deleted
  payment.succeeded
  payment.failed
)

for topic in "${TOPICS[@]}"; do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor "$REPLICATION"
done