package com.fitclub.schedule.domain;

import com.fitclub.common.error.DomainException;
import com.fitclub.common.error.DomainException.Kind;

/** Бизнес-ошибки schedule-service со стабильными машинными кодами. */
public final class ScheduleErrors {

    public static final String INVALID_SESSION_PERIOD = "INVALID_SESSION_PERIOD";
    public static final String INVALID_CAPACITY = "INVALID_CAPACITY";
    public static final String INVALID_TITLE = "INVALID_TITLE";
    public static final String INVALID_TRAINING_TYPE_NAME = "INVALID_TRAINING_TYPE_NAME";
    public static final String TRAINING_TYPE_NOT_FOUND = "TRAINING_TYPE_NOT_FOUND";
    public static final String TRAINING_TYPE_NAME_TAKEN = "TRAINING_TYPE_NAME_TAKEN";
    public static final String TRAINER_NOT_ELIGIBLE = "TRAINER_NOT_ELIGIBLE";
    public static final String SESSION_NOT_EDITABLE = "SESSION_NOT_EDITABLE";
    public static final String SESSION_IN_PAST = "SESSION_IN_PAST";
    public static final String SESSION_ALREADY_STARTED = "SESSION_ALREADY_STARTED";
    public static final String INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION";

    private ScheduleErrors() {
    }

    public static DomainException invalidPeriod() {
        return new DomainException(Kind.VALIDATION, INVALID_SESSION_PERIOD, "Ошибка валидации",
                "Начало занятия должно быть раньше окончания, длительность — не более "
                        + Session.MAX_DURATION.toHours() + " часов.", "ends_at");
    }

    public static DomainException invalidCapacity() {
        return new DomainException(Kind.VALIDATION, INVALID_CAPACITY, "Ошибка валидации",
                "Вместимость должна быть от 1 до " + Session.MAX_CAPACITY + ".", "capacity");
    }

    public static DomainException invalidTitle() {
        return new DomainException(Kind.VALIDATION, INVALID_TITLE, "Ошибка валидации",
                "Название занятия должно содержать от 1 до " + Session.MAX_TITLE_LENGTH + " символов.", "title");
    }

    public static DomainException invalidTrainingTypeName() {
        return new DomainException(Kind.VALIDATION, INVALID_TRAINING_TYPE_NAME, "Ошибка валидации",
                "Название типа тренировки должно содержать от 1 до 100 символов.", "name");
    }

    public static DomainException trainingTypeNotFound() {
        return new DomainException(Kind.VALIDATION, TRAINING_TYPE_NOT_FOUND, "Ошибка валидации",
                "Тип тренировки не найден или отключён.", "training_type_id");
    }

    public static DomainException trainingTypeNameTaken() {
        return new DomainException(Kind.CONFLICT, TRAINING_TYPE_NAME_TAKEN, "Название занято",
                "Тип тренировки с таким названием уже существует.", "name");
    }

    public static DomainException trainerNotEligible() {
        return new DomainException(Kind.CONFLICT, TRAINER_NOT_ELIGIBLE, "Нельзя назначить тренера",
                "Пользователь не найден, отключён или не имеет роли TRAINER.", "trainer_id");
    }

    public static DomainException notEditable() {
        return new DomainException(Kind.CONFLICT, SESSION_NOT_EDITABLE, "Занятие нельзя изменить",
                "После публикации время, тренер и вместимость зафиксированы. Создайте новое занятие, "
                        + "а старое при необходимости отмените.");
    }

    public static DomainException inPast() {
        return new DomainException(Kind.CONFLICT, SESSION_IN_PAST, "Занятие уже в прошлом",
                "Опубликовать можно только занятие, которое ещё не началось.");
    }

    public static DomainException alreadyStarted() {
        return new DomainException(Kind.CONFLICT, SESSION_ALREADY_STARTED, "Занятие уже началось",
                "Начавшееся или прошедшее занятие отменить нельзя.");
    }

    public static DomainException invalidTransition(SessionStatus from, String action) {
        return new DomainException(Kind.CONFLICT, INVALID_STATE_TRANSITION, "Недопустимое действие",
                "Действие «" + action + "» недоступно для занятия в статусе " + from + ".");
    }

    public static DomainException sessionNotFound() {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден", "Занятие не найдено.");
    }
}
