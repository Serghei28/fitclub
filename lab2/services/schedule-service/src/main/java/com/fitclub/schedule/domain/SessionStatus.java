package com.fitclub.schedule.domain;

/** Жизненный цикл занятия: DRAFT → PUBLISHED → CANCELLED (черновик тоже можно отменить). */
public enum SessionStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}
