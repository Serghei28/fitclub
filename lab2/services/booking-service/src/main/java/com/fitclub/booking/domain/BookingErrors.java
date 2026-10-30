package com.fitclub.booking.domain;

import com.fitclub.common.error.DomainException;
import com.fitclub.common.error.DomainException.Kind;

/** Бизнес-ошибки booking-service: стабильные коды и понятные пользователю причины. */
public final class BookingErrors {

    public static final String SESSION_NOT_READY = "SESSION_NOT_READY";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";
    public static final String SESSION_ALREADY_STARTED = "SESSION_ALREADY_STARTED";
    public static final String MEMBERSHIP_NOT_VALID = "MEMBERSHIP_NOT_VALID";
    public static final String ALREADY_BOOKED = "ALREADY_BOOKED";
    public static final String SESSION_FULL = "SESSION_FULL";
    public static final String VISIT_LIMIT_REACHED = "VISIT_LIMIT_REACHED";
    public static final String CANCELLATION_WINDOW_CLOSED = "CANCELLATION_WINDOW_CLOSED";

    private BookingErrors() {
    }

    public static DomainException sessionNotReady() {
        return new DomainException(Kind.CONFLICT, SESSION_NOT_READY, "Запись ещё не открыта",
                "Занятие ещё не появилось в системе записи. Повторите попытку через несколько секунд.");
    }

    public static DomainException sessionCancelled() {
        return new DomainException(Kind.CONFLICT, SESSION_CANCELLED, "Занятие отменено",
                "Занятие отменено, запись на него закрыта.");
    }

    public static DomainException alreadyStarted() {
        return new DomainException(Kind.CONFLICT, SESSION_ALREADY_STARTED, "Занятие уже началось",
                "Записаться можно только на занятие, которое ещё не началось.");
    }

    public static DomainException membershipNotValid() {
        return new DomainException(Kind.CONFLICT, MEMBERSHIP_NOT_VALID, "Нет действующего абонемента",
                "На дату занятия у вас нет действующего абонемента.");
    }

    public static DomainException alreadyBooked() {
        return new DomainException(Kind.CONFLICT, ALREADY_BOOKED, "Вы уже записаны",
                "У вас уже есть запись на это занятие.");
    }

    public static DomainException sessionFull() {
        return new DomainException(Kind.CONFLICT, SESSION_FULL, "Мест нет", "На занятие больше нет свободных мест.");
    }

    public static DomainException visitLimitReached() {
        return new DomainException(Kind.CONFLICT, VISIT_LIMIT_REACHED, "Посещения закончились",
                "Все посещения по вашему абонементу уже использованы или зарезервированы.");
    }

    public static DomainException cancellationWindowClosed(long minutes) {
        return new DomainException(Kind.CONFLICT, CANCELLATION_WINDOW_CLOSED, "Отмена уже недоступна",
                "Отменить запись можно не позднее чем за " + minutes + " минут до начала занятия.");
    }

    public static DomainException bookingNotFound() {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден", "Запись не найдена.");
    }

    public static DomainException sessionNotFound() {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден", "Занятие не найдено.");
    }
}
