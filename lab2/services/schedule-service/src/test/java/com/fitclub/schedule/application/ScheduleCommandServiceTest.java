package com.fitclub.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fitclub.common.error.ApiException;
import com.fitclub.common.error.DomainException;
import com.fitclub.common.web.PageResponse;
import com.fitclub.schedule.application.port.SessionEventPublisher;
import com.fitclub.schedule.application.port.SessionRepository;
import com.fitclub.schedule.application.port.TrainerDirectory;
import com.fitclub.schedule.application.port.TrainingTypeRepository;
import com.fitclub.schedule.application.port.TransactionRunner;
import com.fitclub.schedule.domain.ScheduleErrors;
import com.fitclub.schedule.domain.Session;
import com.fitclub.schedule.domain.SessionStatus;
import com.fitclub.schedule.domain.TrainingType;

/** Сценарии расписания на fake-адаптерах: без Spring, БД, HTTP и RabbitMQ. */
class ScheduleCommandServiceTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    final Map<UUID, Session> sessions = new HashMap<>();
    final Map<UUID, TrainingType> types = new HashMap<>();
    final Map<UUID, TrainerDirectory.TrainerStatus> trainers = new HashMap<>();
    final List<String> events = new ArrayList<>();
    boolean authDown;
    int transactions;

    ScheduleCommandService service;
    UUID typeId;
    UUID trainerId;

    @BeforeEach
    void setUp() {
        SessionRepository sessionRepo = new SessionRepository() {
            public Optional<Session> findById(UUID id) {
                return Optional.ofNullable(sessions.get(id));
            }

            public void add(Session s) {
                sessions.put(s.id(), s);
            }

            public void update(Session s) {
                sessions.put(s.id(), s);
            }

            public PageResponse<Session> search(Criteria c, int page, int size) {
                return PageResponse.of(List.copyOf(sessions.values()), page, size, sessions.size());
            }
        };
        TrainingTypeRepository typeRepo = new TrainingTypeRepository() {
            public Optional<TrainingType> findById(UUID id) {
                return Optional.ofNullable(types.get(id));
            }

            public List<TrainingType> findAllActive() {
                return List.copyOf(types.values());
            }

            public Map<UUID, String> findNames(Collection<UUID> ids) {
                return ids.stream().collect(Collectors.toMap(id -> id, id -> types.get(id).name()));
            }

            public void add(TrainingType t) {
                types.put(t.id(), t);
            }
        };
        TrainerDirectory directory = userId -> {
            if (authDown) {
                throw ApiException.dependencyUnavailable();
            }
            return Optional.ofNullable(trainers.get(userId));
        };
        SessionEventPublisher publisher = new SessionEventPublisher() {
            public void sessionPublished(Session s) {
                events.add("SessionPublished:" + s.id() + ":v" + s.version());
            }

            public void sessionCancelled(Session s) {
                events.add("SessionCancelled:" + s.id() + ":v" + s.version());
            }
        };
        TransactionRunner tx = new TransactionRunner() {
            public <T> T inTransaction(Supplier<T> work) {
                transactions++;
                return work.get();
            }

            public <T> T readOnly(Supplier<T> work) {
                return work.get();
            }
        };
        service = new ScheduleCommandService(sessionRepo, typeRepo, new TrainerVerification(directory), publisher, tx,
                Clock.fixed(NOW, ZoneOffset.UTC));

        typeId = service.createTrainingType("Йога", null).id();
        trainerId = UUID.randomUUID();
        trainers.put(trainerId, new TrainerDirectory.TrainerStatus(trainerId, true, true, "Ольга Тренер"));
    }

    Session draft() {
        return service.createDraft(new ScheduleCommandService.CreateSession(typeId, trainerId, "Утренняя йога",
                NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), 10));
    }

    static void assertCode(Throwable e, String code) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).code()).isEqualTo(code);
    }

    @Test
    void draftKeepsTrainerNameSnapshotFromAuth() {
        Session session = draft();
        assertThat(session.status()).isEqualTo(SessionStatus.DRAFT);
        assertThat(session.trainerDisplayName()).isEqualTo("Ольга Тренер");
        assertThat(events).isEmpty();
    }

    @Test
    void userWithoutTrainerRoleCannotBeAssigned() {
        UUID client = UUID.randomUUID();
        trainers.put(client, new TrainerDirectory.TrainerStatus(client, true, false, "Клиент"));
        assertThatThrownBy(() -> service.createDraft(new ScheduleCommandService.CreateSession(typeId, client, "Йога",
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), 5)))
                .satisfies(e -> assertCode(e, ScheduleErrors.TRAINER_NOT_ELIGIBLE));
        assertThatThrownBy(() -> service.createDraft(new ScheduleCommandService.CreateSession(typeId,
                UUID.randomUUID(), "Йога", NOW.plusSeconds(3600), NOW.plusSeconds(7200), 5)))
                .satisfies(e -> assertCode(e, ScheduleErrors.TRAINER_NOT_ELIGIBLE));
    }

    @Test
    void publishCreatesEventWithVersionInsideSameTransaction() {
        Session session = draft();
        int before = transactions;
        Session published = service.publish(session.id());
        assertThat(published.status()).isEqualTo(SessionStatus.PUBLISHED);
        assertThat(events).containsExactly("SessionPublished:" + session.id() + ":v1");
        assertThat(transactions - before).isEqualTo(1);
    }

    @Test
    void trainerWhoLostRoleBlocksPublication() {
        Session session = draft();
        trainers.put(trainerId, new TrainerDirectory.TrainerStatus(trainerId, true, false, "Ольга Тренер"));
        assertThatThrownBy(() -> service.publish(session.id()))
                .satisfies(e -> assertCode(e, ScheduleErrors.TRAINER_NOT_ELIGIBLE));
        assertThat(sessions.get(session.id()).status()).isEqualTo(SessionStatus.DRAFT);
        assertThat(events).isEmpty();
    }

    @Test
    void authOutageIsReportedAndDoesNotPublish() {
        Session session = draft();
        authDown = true;
        assertThatThrownBy(() -> service.publish(session.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DEPENDENCY_UNAVAILABLE"));
        assertThat(sessions.get(session.id()).status()).isEqualTo(SessionStatus.DRAFT);
        assertThat(events).isEmpty();
    }

    @Test
    void cancellingPublishedSessionEmitsEventOnce() {
        Session session = draft();
        service.publish(session.id());
        service.cancel(session.id(), "тренер заболел");
        service.cancel(session.id(), "повтор");
        assertThat(events).containsExactly("SessionPublished:" + session.id() + ":v1",
                "SessionCancelled:" + session.id() + ":v2");
    }

    @Test
    void cancellingDraftEmitsNoEvent() {
        Session session = draft();
        service.cancel(session.id(), null);
        assertThat(sessions.get(session.id()).status()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(events).isEmpty();
    }

    @Test
    void publishedSessionCannotBeEdited() {
        Session session = draft();
        service.publish(session.id());
        assertThatThrownBy(() -> service.editDraft(session.id(), new ScheduleCommandService.EditSession(null, null,
                null, null, null, 50))).satisfies(e -> assertCode(e, ScheduleErrors.SESSION_NOT_EDITABLE));
    }
}
