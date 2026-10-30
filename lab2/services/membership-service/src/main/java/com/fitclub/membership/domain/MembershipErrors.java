package com.fitclub.membership.domain;

import com.fitclub.common.error.DomainException;
import com.fitclub.common.error.DomainException.Kind;

/** Бизнес-ошибки membership-service со стабильными машинными кодами. */
public final class MembershipErrors {

    public static final String INVALID_PLAN = "INVALID_PLAN";
    public static final String PLAN_NAME_TAKEN = "PLAN_NAME_TAKEN";
    public static final String PLAN_NOT_AVAILABLE = "PLAN_NOT_AVAILABLE";
    public static final String PENDING_ORDER_EXISTS = "PENDING_ORDER_EXISTS";
    public static final String ACTIVE_MEMBERSHIP_EXISTS = "ACTIVE_MEMBERSHIP_EXISTS";
    public static final String INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION";

    private MembershipErrors() {
    }

    public static DomainException invalidPlan(String field, String detail) {
        return new DomainException(Kind.VALIDATION, INVALID_PLAN, "Ошибка валидации", detail, field);
    }

    public static DomainException planNameTaken() {
        return new DomainException(Kind.CONFLICT, PLAN_NAME_TAKEN, "Название занято",
                "Тариф с таким названием уже существует.", "name");
    }

    public static DomainException planNotAvailable() {
        return new DomainException(Kind.CONFLICT, PLAN_NOT_AVAILABLE, "Тариф недоступен",
                "Тариф не найден или больше не продаётся.", "plan_id");
    }

    public static DomainException pendingOrderExists() {
        return new DomainException(Kind.CONFLICT, PENDING_ORDER_EXISTS, "Есть неоплаченный заказ",
                "У вас уже есть заказ другого тарифа, ожидающий оплаты. Оплатите его или дождитесь отказа оплаты.");
    }

    public static DomainException activeMembershipExists() {
        return new DomainException(Kind.CONFLICT, ACTIVE_MEMBERSHIP_EXISTS, "Абонемент уже есть",
                "У вас уже есть действующий абонемент. Новый можно купить после окончания текущего.");
    }

    public static DomainException invalidTransition(OrderStatus from, String action) {
        return new DomainException(Kind.CONFLICT, INVALID_STATE_TRANSITION, "Недопустимое действие",
                "Действие «" + action + "» недоступно для заказа в статусе " + from + ".");
    }

    public static DomainException notFound(String what) {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден", what + " не найден.");
    }
}
