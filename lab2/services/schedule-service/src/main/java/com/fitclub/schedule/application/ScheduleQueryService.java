package com.fitclub.schedule.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.common.web.PageResponse;
import com.fitclub.schedule.application.port.SessionRepository;
import com.fitclub.schedule.application.port.TrainingTypeRepository;
import com.fitclub.schedule.application.port.TransactionRunner;
import com.fitclub.schedule.domain.ScheduleErrors;
import com.fitclub.schedule.domain.Session;
import com.fitclub.schedule.domain.SessionStatus;
import com.fitclub.schedule.domain.TrainingType;

/**
 * Чтение расписания. Черновики видит только ADMIN: для остальных DRAFT как будто не существует (404).
 * Свободные места здесь не считаются — их источник booking-service (GET /api/availability).
 */
@Service
public class ScheduleQueryService {

    private static final Set<SessionStatus> MEMBER_VISIBLE = EnumSet.of(SessionStatus.PUBLISHED,
            SessionStatus.CANCELLED);

    private final SessionRepository sessions;
    private final TrainingTypeRepository trainingTypes;
    private final TransactionRunner tx;

    public ScheduleQueryService(SessionRepository sessions, TrainingTypeRepository trainingTypes,
            TransactionRunner tx) {
        this.sessions = sessions;
        this.trainingTypes = trainingTypes;
        this.tx = tx;
    }

    /** Занятие вместе с названием типа (из той же БD schedule_db). */
    public record SessionView(Session session, String trainingTypeName) {
    }

    public List<TrainingType> activeTrainingTypes() {
        return tx.readOnly(trainingTypes::findAllActive);
    }

    public SessionView session(UUID sessionId, AuthenticatedUser viewer) {
        return tx.readOnly(() -> {
            Session session = sessions.findById(sessionId)
                    .filter(s -> s.isVisibleToMembers() || viewer.isAdmin())
                    .orElseThrow(ScheduleErrors::sessionNotFound);
            return new SessionView(session, trainingTypes.findNames(List.of(session.trainingTypeId()))
                    .get(session.trainingTypeId()));
        });
    }

    public PageResponse<SessionView> search(Instant from, Instant to, UUID trainerId, SessionStatus status,
            AuthenticatedUser viewer, int page, int size) {
        Set<SessionStatus> statuses = viewer.isAdmin()
                ? (status == null ? EnumSet.allOf(SessionStatus.class) : EnumSet.of(status))
                : (status == null || status == SessionStatus.DRAFT ? MEMBER_VISIBLE : EnumSet.of(status));
        return find(new SessionRepository.Criteria(from, to, trainerId, statuses), page, size);
    }

    /** Собственные занятия тренера (без черновиков). */
    public PageResponse<SessionView> trainerSessions(UUID trainerId, Instant from, Instant to, int page, int size) {
        return find(new SessionRepository.Criteria(from, to, trainerId, MEMBER_VISIBLE), page, size);
    }

    private PageResponse<SessionView> find(SessionRepository.Criteria criteria, int page, int size) {
        return tx.readOnly(() -> {
            PageResponse<Session> result = sessions.search(criteria, page, Math.min(size, PageResponse.MAX_SIZE));
            Map<UUID, String> names = trainingTypes.findNames(
                    result.items().stream().map(Session::trainingTypeId).distinct().toList());
            return result.map(s -> new SessionView(s, names.get(s.trainingTypeId())));
        });
    }
}
