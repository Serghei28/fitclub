package com.fitclub.schedule.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fitclub.schedule.application.port.SessionEventPublisher;
import com.fitclub.schedule.application.port.SessionRepository;
import com.fitclub.schedule.application.port.TrainingTypeRepository;
import com.fitclub.schedule.application.port.TransactionRunner;
import com.fitclub.schedule.domain.ScheduleErrors;
import com.fitclub.schedule.domain.Session;
import com.fitclub.schedule.domain.SessionStatus;
import com.fitclub.schedule.domain.TrainerSnapshot;
import com.fitclub.schedule.domain.TrainingType;

/**
 * Сценарии ADMIN над расписанием: типы тренировок и жизненный цикл занятия.
 * Шаблон каждого сценария с тренером: (1) проверка тренера в Auth вне транзакции →
 * (2) короткая локальная транзакция: перечитать занятие, применить правило домена, сохранить и,
 * если нужно, записать событие в outbox. Изменение и событие фиксируются вместе.
 * Права ADMIN проверяются в контроллере (@PreAuthorize) — здесь только бизнес-правила.
 */
@Service
public class ScheduleCommandService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleCommandService.class);

    private final SessionRepository sessions;
    private final TrainingTypeRepository trainingTypes;
    private final TrainerVerification trainers;
    private final SessionEventPublisher events;
    private final TransactionRunner tx;
    private final Clock clock;

    public ScheduleCommandService(SessionRepository sessions, TrainingTypeRepository trainingTypes,
            TrainerVerification trainers, SessionEventPublisher events, TransactionRunner tx, Clock clock) {
        this.sessions = sessions;
        this.trainingTypes = trainingTypes;
        this.trainers = trainers;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public TrainingType createTrainingType(String name, String description) {
        TrainingType type = TrainingType.create(UUID.randomUUID(), name, description, clock.instant());
        tx.inTransaction(() -> {
            trainingTypes.add(type);
            return type;
        });
        log.info("Training type created: training_type_id={}", type.id());
        return type;
    }

    public record CreateSession(UUID trainingTypeId, UUID trainerId, String title, Instant startsAt, Instant endsAt,
            int capacity) {
    }

    public Session createDraft(CreateSession command) {
        TrainerSnapshot trainer = trainers.verify(command.trainerId());
        Session created = tx.inTransaction(() -> {
            requireActiveType(command.trainingTypeId());
            Session session = Session.createDraft(UUID.randomUUID(), command.trainingTypeId(), trainer,
                    command.title(), command.startsAt(), command.endsAt(), command.capacity(), clock.instant());
            sessions.add(session);
            return session;
        });
        log.info("Session draft created: session_id={}", created.id());
        return created;
    }

    public record EditSession(UUID trainingTypeId, UUID trainerId, String title, Instant startsAt, Instant endsAt,
            Integer capacity) {
    }

    public Session editDraft(UUID sessionId, EditSession command) {
        Session current = tx.readOnly(() -> load(sessionId));
        if (current.status() != SessionStatus.DRAFT) {
            throw ScheduleErrors.notEditable();
        }
        TrainerSnapshot newTrainer = command.trainerId() != null && !command.trainerId().equals(current.trainerId())
                ? trainers.verify(command.trainerId())
                : null;
        Session edited = tx.inTransaction(() -> {
            Session session = load(sessionId);
            if (command.trainingTypeId() != null) {
                requireActiveType(command.trainingTypeId());
            }
            session.editDraft(new Session.DraftChanges(command.trainingTypeId(), command.title(), command.startsAt(),
                    command.endsAt(), command.capacity()), newTrainer, clock.instant());
            sessions.update(session);
            return session;
        });
        log.info("Session draft edited: session_id={}", sessionId);
        return edited;
    }

    public Session publish(UUID sessionId) {
        Session current = tx.readOnly(() -> load(sessionId));
        TrainerSnapshot verified = trainers.verify(current.trainerId());
        Session published = tx.inTransaction(() -> {
            Session session = load(sessionId);
            if (!session.trainerId().equals(verified.trainerId())) {
                // Тренера сменили между проверкой и транзакцией: безопаснее попросить повторить
                throw ScheduleErrors.trainerNotEligible();
            }
            session.publish(verified, clock.instant());
            sessions.update(session);
            events.sessionPublished(session);
            return session;
        });
        log.info("Session published: session_id={}, version={}", sessionId, published.version());
        return published;
    }

    public Session cancel(UUID sessionId, String reason) {
        return tx.inTransaction(() -> {
            Session session = load(sessionId);
            Session.CancelOutcome outcome = session.cancel(reason, clock.instant());
            if (outcome == Session.CancelOutcome.ALREADY_CANCELLED) {
                return session;
            }
            sessions.update(session);
            if (outcome == Session.CancelOutcome.PUBLISHED_CANCELLED) {
                events.sessionCancelled(session);
            }
            log.info("Session cancelled: session_id={}, outcome={}", sessionId, outcome);
            return session;
        });
    }

    private Session load(UUID sessionId) {
        return sessions.findById(sessionId).orElseThrow(ScheduleErrors::sessionNotFound);
    }

    private void requireActiveType(UUID trainingTypeId) {
        trainingTypes.findById(trainingTypeId).filter(TrainingType::active)
                .orElseThrow(ScheduleErrors::trainingTypeNotFound);
    }
}
