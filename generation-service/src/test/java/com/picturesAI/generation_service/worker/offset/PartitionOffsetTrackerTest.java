package com.picturesAI.generation_service.worker.offset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionOffsetTrackerTest {

    @Test
    void inOrderCompletionAdvancesCommit() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        for (long i = 0; i < 5; i++) {
            t.register(i);
        }
        for (long i = 0; i < 5; i++) {
            t.markCompleted(i);
        }

        assertThat(t.safeCommitOffset()).isEqualTo(5);
    }

    @Test
    void outOfOrderCompletionHoldsPrefix() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        for (long i = 0; i < 5; i++) {
            t.register(i);
        }

        t.markCompleted(2);
        assertThat(t.safeCommitOffset()).isZero();

        t.markCompleted(0);
        assertThat(t.safeCommitOffset()).isEqualTo(1);

        t.markCompleted(1);
        assertThat(t.safeCommitOffset()).isEqualTo(3);

        t.markCompleted(4);
        assertThat(t.safeCommitOffset()).isEqualTo(3);

        t.markCompleted(3);
        assertThat(t.safeCommitOffset()).isEqualTo(5);
    }

    @Test
    void emptyPrefixWhenNothingCompleted() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        t.register(0);
        t.register(1);

        assertThat(t.safeCommitOffset()).isZero();
    }

    @Test
    void noRegisterMeansNothingToCommit() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();

        assertThat(t.safeCommitOffset()).isZero();
    }

    @Test
    void resetClearsStateAndNextRegisterReinitializes() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        t.register(0);
        t.register(1);
        t.register(2);
        t.markCompleted(0);
        t.markCompleted(1);
        assertThat(t.safeCommitOffset()).isEqualTo(2);

        t.reset();
        assertThat(t.safeCommitOffset()).isZero();

        t.register(5);
        t.markCompleted(5);
        assertThat(t.safeCommitOffset()).isEqualTo(6);
    }

    @Test
    void duplicateCompletionIsIdempotent() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        t.register(0);
        t.register(1);

        t.markCompleted(0);
        t.markCompleted(0);
        t.markCompleted(0);

        assertThat(t.safeCommitOffset()).isEqualTo(1);
    }

    @Test
    void completionForAlreadyCommittedOffsetIsIgnored() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        for (long i = 0; i < 3; i++) {
            t.register(i);
        }
        for (long i = 0; i < 3; i++) {
            t.markCompleted(i);
        }
        assertThat(t.safeCommitOffset()).isEqualTo(3);

        t.markCompleted(0);
        t.markCompleted(1);

        assertThat(t.safeCommitOffset()).isEqualTo(3);
    }

    @Test
    void firstRegisterAfterResetSetsLowWaterMark() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        t.register(100);
        t.markCompleted(100);
        assertThat(t.safeCommitOffset()).isEqualTo(101);

        t.reset();
        t.register(7);
        assertThat(t.safeCommitOffset()).isEqualTo(7);
    }

    @Test
    void sparseCompletionDoesNotAdvance() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        t.register(0);
        t.register(10);
        t.register(20);

        t.markCompleted(20);
        assertThat(t.safeCommitOffset()).isZero();

        t.markCompleted(10);
        assertThat(t.safeCommitOffset()).isZero();

        t.markCompleted(0);
        assertThat(t.safeCommitOffset()).isEqualTo(1);
    }

    @Test
    void advanceSkipsOverBufferedCompletedRange() {
        PartitionOffsetTracker t = new PartitionOffsetTracker();
        for (long i = 0; i < 10; i++) {
            t.register(i);
        }
        t.markCompleted(5);
        t.markCompleted(6);
        t.markCompleted(7);
        assertThat(t.safeCommitOffset()).isZero();

        t.markCompleted(0);
        assertThat(t.safeCommitOffset()).isEqualTo(1);

        t.markCompleted(1);
        t.markCompleted(2);
        t.markCompleted(3);
        t.markCompleted(4);
        assertThat(t.safeCommitOffset()).isEqualTo(8);
    }
}