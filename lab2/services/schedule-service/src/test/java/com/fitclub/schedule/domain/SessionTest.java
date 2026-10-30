package com.fitclub.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitclub.common.error.DomainException;

class SessionTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final TrainerSnapshot TRAINER = new TrainerSnapshot(UUID.randomUUID(), "Ольга Тренер");

    static Session draftStartingIn(Duration fromNow) {
        Instant start = NOW.plus(fromNow);
        return Session.createDraft(UUID.randomUUID(), UUID.randomUUID(), TRAINER, "Йога", start,
                start.plus(Duration.ofHours(1)), 10, NOW);
    }

    static void assertCode(Throwable e, String code) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).code()).isEqualTo(code);
    }

    @Test
    void endMustBeAfterStart() {
        assertThatThrownBy(() -> Session.createDraft(UUID.randomUUID(), UUID.randomUUID(), TRAINER, "Йога", NOW, NOW,
                10, NOW)).satisfies(e -> assertCode(e, ScheduleErrors.INVALID_SESSION_PERIOD));
        assertThatThrownBy(() -> Session.createDraft(UUID.randomUUID(), UUID.randomUUID(), TRAINER, "Йога",
                NOW.plusSeconds(60), NOW, 10, NOW)).satisfies(e -> assertCode(e, ScheduleErrors.INVALID_SESSION_PERIOD));
    }

    @Test
    void capacityMustBePositiveAndBounded() {
        assertThatThrownBy(() -> Session.createDraft(UUID.randomUUID(), UUID.randomUUID(), TRAINER, "Йога", NOW,
                NOW.plusSeconds(3600), 0, NOW)).satisfies(e -> assertCode(e, ScheduleErrors.INVALID_CAPACITY));
        assertThatThrownBy(() -> Session.createDraft(UUID.randomUUID(), UUID.randomUUID(), TRAINER, "Йога", NOW,
                NOW.plusSeconds(3600), 501, NOW)).satisfies(e -> assertCode(e, ScheduleErrors.INVALID_CAPACITY));
    }

    @Test
    void draftCanBeEditedButPublishedSnapshotIsFrozen() {
        Session session = draftStartingIn(Duration.ofDays(1));
        session.editDraft(new Session.DraftChanges(null, "Хатха-йога", null, null, 12), null, NOW);
        assertThat(session.capacity()).isEqualTo(12);
        assertThat(session.version()).isEqualTo(1);

        session.publish(TRAINER, NOW);

        assertThatThrownBy(() -> session.editDraft(new Session.DraftChanges(null, null, null, null, 20), null, NOW))
                .satisfies(e -> assertCode(e, ScheduleErrors.SESSION_NOT_EDITABLE));
    }

    @Test
    void onlyFutureSessionCanBePublished() {
        Session past = draftStartingIn(Duration.ZERO);
        assertThatThrownBy(() -> past.publish(TRAINER, NOW)).satisfies(e -> assertCode(e, ScheduleErrors.SESSION_IN_PAST));

        Session future = draftStartingIn(Duration.ofMinutes(1));
        future.publish(new TrainerSnapshot(TRAINER.trainerId(), "Ольга Новикова"), NOW);
        assertThat(future.status()).isEqualTo(SessionStatus.PUBLISHED);
        assertThat(future.trainerDisplayName()).isEqualTo("Ольга Новикова");
        assertThat(future.version()).isEqualTo(1);
    }

    @Test
    void publishingTwiceIsInvalidTransition() {
        Session session = draftStartingIn(Duration.ofDays(1));
        session.publish(TRAINER, NOW);
        assertThatThrownBy(() -> session.publish(TRAINER, NOW))
                .satisfies(e -> assertCode(e, ScheduleErrors.INVALID_STATE_TRANSITION));
    }

    @Test
    void cancellationOutcomes() {
        Session draft = draftStartingIn(Duration.ofDays(1));
        assertThat(draft.cancel("не нужно", NOW)).isEqualTo(Session.CancelOutcome.DRAFT_DISCARDED);

        Session published = draftStartingIn(Duration.ofDays(1));
        published.publish(TRAINER, NOW);
        assertThat(published.cancel("тренер заболел", NOW)).isEqualTo(Session.CancelOutcome.PUBLISHED_CANCELLED);
        assertThat(published.version()).isEqualTo(2);
        assertThat(published.cancel("ещё раз", NOW)).isEqualTo(Session.CancelOutcome.ALREADY_CANCELLED);
        assertThat(published.version()).isEqualTo(2);
        assertThat(published.cancelReason()).isEqualTo("тренер заболел");
    }

    @Test
    void startedSessionCannotBeCancelled() {
        Session session = draftStartingIn(Duration.ofHours(1));
        session.publish(TRAINER, NOW);
        assertThatThrownBy(() -> session.cancel(null, NOW.plus(Duration.ofHours(1))))
                .satisfies(e -> assertCode(e, ScheduleErrors.SESSION_ALREADY_STARTED));
    }
}
