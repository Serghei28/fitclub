package com.fitclub.notification.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Тексты уведомлений на русском; время показывается в часовом поясе клуба (Europe/Chisinau). */
public final class NotificationTexts {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ZoneId zone;

    public NotificationTexts(ZoneId zone) {
        this.zone = zone;
    }

    public String time(Instant instant) {
        return instant == null ? "—" : FORMAT.withZone(zone).format(instant);
    }

    public String membershipActivated(String planName, Instant startsAt, Instant endsAt, Integer visitLimit) {
        String visits = visitLimit == null ? "без ограничения посещений" : "посещений: " + visitLimit;
        return "Тариф «" + planName + "» действует с " + time(startsAt) + " до " + time(endsAt) + ", " + visits + ".";
    }

    public String bookingConfirmed(String title, Instant startsAt) {
        return "Вы записаны на «" + title + "», начало " + time(startsAt) + ".";
    }

    public String bookingCancelledByUser(String title, Instant startsAt) {
        return "Вы отменили запись на «" + title + "» (" + time(startsAt) + "). Посещение возвращено на абонемент.";
    }

    public String sessionCancelled(String title, Instant startsAt) {
        return "Занятие «" + title + "» (" + time(startsAt) + ") отменено клубом. Посещение возвращено на абонемент.";
    }

    public String reminder(String title, Instant startsAt) {
        return "Напоминаем: «" + title + "» начнётся " + time(startsAt) + ".";
    }
}
