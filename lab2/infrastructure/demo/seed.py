"""Наполнение учебного стенда FitClub demo-данными.

Запускается только явно:  docker compose --profile demo run --rm demo-seed
Все данные создаются через публичный API (Gateway), а не записью в базы: каждый сервис сам проверяет
правила и публикует события. Скрипт повторяемый: существующие пользователи, тарифы, типы и занятия
не дублируются, а повторные POST идут с теми же детерминированными Idempotency-Key.
Пароли не выводятся: они читаются из файлов секретов.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, datetime, time as dtime, timedelta, timezone

API = os.environ.get("FITCLUB_API", "http://gateway:8080").rstrip("/")
SECRETS = os.environ.get("FITCLUB_SECRETS_DIR", "/run/secrets")
ADMIN_EMAIL = os.environ.get("FITCLUB_AUTH_INITIAL_ADMIN_EMAIL", "admin@fitclub.local")
KEY_NAMESPACE = uuid.UUID("6f4b8f8e-3c1a-4c0e-9a57-0f1c2d3e4f50")

try:
    from zoneinfo import ZoneInfo
    CLUB_ZONE = ZoneInfo("Europe/Chisinau")
except Exception:  # в образе нет базы часовых поясов — берём зимнее смещение Кишинёва
    CLUB_ZONE = timezone(timedelta(hours=2))

TRAINERS = [
    ("trainer.anna@fitclub.demo", "Анна Руснак"),
    ("trainer.boris@fitclub.demo", "Борис Чебан"),
]
CLIENTS = [
    ("client.elena@fitclub.demo", "Елена Морару"),
    ("client.dmitry@fitclub.demo", "Дмитрий Лупу"),
    ("client.olga@fitclub.demo", "Ольга Попеску"),
]
PLANS = [
    {"name": "Старт — 4 посещения", "duration_days": 30, "visit_limit": 4, "price_minor": 30000},
    {"name": "Стандарт — 8 посещений", "duration_days": 30, "visit_limit": 8, "price_minor": 45000},
    {"name": "Безлимит", "duration_days": 30, "visit_limit": None, "price_minor": 90000},
]
TRAINING_TYPES = [
    ("Йога", "Растяжка, дыхание и баланс. Подходит для любого уровня."),
    ("Пилатес", "Укрепление мышц корпуса и осанка."),
    ("Функциональный тренинг", "Силовые и кардио-упражнения в формате круговой тренировки."),
]
# (день от завтрашнего, начало по Кишинёву, минут, тип, тренер, название, мест)
SESSIONS = [
    (0, "08:00", 60, "Йога", 0, "Утренняя йога", 12),
    (0, "19:00", 60, "Функциональный тренинг", 1, "Функциональный вечер", 10),
    (1, "09:00", 55, "Пилатес", 0, "Пилатес: индивидуально", 1),
    (1, "18:30", 60, "Йога", 0, "Вечерняя йога", 8),
    (2, "19:00", 60, "Функциональный тренинг", 1, "Круговая тренировка", 15),
]
MEMBERSHIPS = [("client.elena@fitclub.demo", "Стандарт — 8 посещений"),
               ("client.dmitry@fitclub.demo", "Безлимит")]
BOOKING = ("client.elena@fitclub.demo", "Утренняя йога")


class ApiError(Exception):
    def __init__(self, status, problem):
        super().__init__(f"HTTP {status}: {problem.get('code')} {problem.get('detail')}")
        self.status = status
        self.code = problem.get("code")


def log(message):
    print(message, flush=True)


def read_secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
        return f.read().strip()


def key(*parts):
    """Детерминированный Idempotency-Key: повторный запуск повторяет те же операции, а не создаёт новые."""
    return str(uuid.uuid5(KEY_NAMESPACE, "|".join(parts)))


def call(method, path, body=None, token=None, idempotency_key=None):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            problem = json.loads(raw) if raw else {}
        except ValueError:
            problem = {}
        raise ApiError(error.code, problem) from None


def login(email, password):
    return call("POST", "/api/auth/login", {"email": email, "password": password})["access_token"]


def wait_for_api(admin_password):
    """Сервисы могут ещё стартовать: ждём, пока вход администратора станет возможен."""
    deadline = time.monotonic() + 240
    while True:
        try:
            return login(ADMIN_EMAIL, admin_password)
        except ApiError as error:
            if error.status in (401, 403) and error.code == "INVALID_CREDENTIALS":
                sys.exit("Вход администратора отклонён: проверьте FITCLUB_AUTH_INITIAL_ADMIN_EMAIL и секрет.")
            reason = str(error)
        except OSError as error:
            reason = str(error)
        if time.monotonic() > deadline:
            sys.exit("API не стал доступен за 4 минуты: " + reason)
        log("Жду готовности API…")
        time.sleep(5)


def ensure_user(admin, email, name, password, roles):
    try:
        call("POST", "/api/auth/register", {"email": email, "password": password, "display_name": name})
        log(f"  создан пользователь {email}")
    except ApiError as error:
        if error.code != "EMAIL_ALREADY_USED":
            raise
        log(f"  пользователь {email} уже есть")
    page = call("GET", "/api/admin/users?" + urllib.parse.urlencode({"q": email}), token=admin)
    user = next(u for u in page["items"] if u["email"] == email)
    if sorted(user["roles"]) != sorted(roles):
        call("PATCH", f"/api/admin/users/{user['id']}/roles", {"roles": roles}, token=admin)
        log(f"  роли {email}: {', '.join(roles)}")
    return user


def ensure_plans(admin):
    existing = {p["name"]: p for p in call("GET", "/api/plans?include_inactive=true", token=admin)}
    for plan in PLANS:
        if plan["name"] not in existing:
            existing[plan["name"]] = call("POST", "/api/plans", {**plan, "currency": "MDL"}, token=admin)
            log(f"  создан тариф «{plan['name']}»")
        elif not existing[plan["name"]]["active"]:
            existing[plan["name"]] = call("PATCH", f"/api/plans/{existing[plan['name']]['id']}",
                                          {"active": True}, token=admin)
            log(f"  тариф «{plan['name']}» возвращён в продажу")
    return existing


def ensure_types(admin):
    existing = {t["name"]: t for t in call("GET", "/api/training-types", token=admin)}
    for name, description in TRAINING_TYPES:
        if name not in existing:
            existing[name] = call("POST", "/api/training-types",
                                  {"name": name, "description": description}, token=admin)
            log(f"  создан тип тренировки «{name}»")
    return existing


def club_time_to_utc(day, hhmm):
    hours, minutes = map(int, hhmm.split(":"))
    return datetime.combine(day, dtime(hours, minutes), CLUB_ZONE).astimezone(timezone.utc)


def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def ensure_sessions(admin, types, trainers):
    tomorrow = datetime.now(CLUB_ZONE).date() + timedelta(days=1)
    window = {"from": iso(datetime.now(timezone.utc)), "to": iso(datetime.now(timezone.utc) + timedelta(days=10)),
              "size": 100}
    existing = call("GET", "/api/sessions?" + urllib.parse.urlencode(window), token=admin)["items"]
    by_key = {(s["title"], s["starts_at"]): s for s in existing if s["status"] != "CANCELLED"}
    result = {}
    for offset, start, minutes, type_name, trainer_index, title, capacity in SESSIONS:
        starts = club_time_to_utc(tomorrow + timedelta(days=offset), start)
        session = by_key.get((title, iso(starts)))
        if session is None:
            session = call("POST", "/api/sessions", {
                "training_type_id": types[type_name]["id"],
                "trainer_id": trainers[trainer_index]["id"],
                "title": title,
                "starts_at": iso(starts),
                "ends_at": iso(starts + timedelta(minutes=minutes)),
                "capacity": capacity,
            }, token=admin)
            log(f"  создано занятие «{title}» {starts.astimezone(CLUB_ZONE):%d.%m %H:%M}")
        if session["status"] == "DRAFT":
            session = call("POST", f"/api/sessions/{session['id']}/publish", token=admin)
            log(f"  опубликовано «{title}»")
        result[title] = session
    return result


def ensure_membership(email, password, plan):
    token = login(email, password)
    current = [m for m in call("GET", "/api/memberships/me", token=token) if m["status"] in ("ACTIVE", "UPCOMING")]
    if current:
        log(f"  у {email} уже есть абонемент «{current[0]['plan_name']}»")
        return True
    day = date.today().isoformat()
    order = call("POST", "/api/membership-orders", {"plan_id": plan["id"]}, token=token,
                 idempotency_key=key("order", email, plan["id"], day))
    payment = call("POST", "/api/payments", {"membership_order_id": order["id"]}, token=token,
                   idempotency_key=key("payment", order["id"]))
    if payment["status"] == "PENDING":
        try:
            payment = call("POST", f"/api/payments/{payment['id']}/simulate", {"outcome": "SUCCESS"}, token=token,
                           idempotency_key=key("simulate", payment["id"]))
        except ApiError as error:
            if error.status == 404:
                log("  тестовая оплата выключена (нет профиля demo в FITCLUB_PROFILES) — абонементы пропущены")
                return False
            raise
    if payment["status"] != "SUCCEEDED":
        log(f"  платёж {email} в статусе {payment['status']} — абонемент не оформлен")
        return False
    # Абонемент активируется асинхронно через событие payment.succeeded
    for _ in range(30):
        if any(m["order_id"] == order["id"] for m in call("GET", "/api/memberships/me", token=token)):
            log(f"  {email}: абонемент «{plan['name']}» активирован (тестовая оплата)")
            return True
        time.sleep(1)
    log(f"  {email}: оплата прошла, абонемент ещё активируется")
    return False


def ensure_booking(email, password, session):
    token = login(email, password)
    for attempt in range(15):
        try:
            call("POST", "/api/bookings", {"session_id": session["id"]}, token=token,
                 idempotency_key=key("booking", email, session["id"]))
            log(f"  {email} записан(а) на «{session['title']}»")
            return
        except ApiError as error:
            if error.code == "ALREADY_BOOKED":
                log(f"  {email} уже записан(а) на «{session['title']}»")
                return
            # проекции занятия и абонемента приходят событиями — даём им несколько секунд
            if error.code not in ("SESSION_NOT_READY", "MEMBERSHIP_NOT_VALID") or attempt == 14:
                log(f"  запись {email} не выполнена: {error.code}")
                return
            time.sleep(2)


def main():
    admin_password = read_secret("auth_initial_admin_password")
    demo_password = read_secret("demo_users_password")
    log(f"FitClub demo-seed → {API}")
    admin = wait_for_api(admin_password)

    log("Пользователи:")
    trainers = [ensure_user(admin, email, name, demo_password, ["CLIENT", "TRAINER"]) for email, name in TRAINERS]
    for email, name in CLIENTS:
        ensure_user(admin, email, name, demo_password, ["CLIENT"])
    log("Тарифы:")
    plans = ensure_plans(admin)
    log("Типы тренировок:")
    types = ensure_types(admin)
    log("Занятия (относительно завтрашнего дня):")
    sessions = ensure_sessions(admin, types, trainers)
    log("Абонементы:")
    activated = {email: ensure_membership(email, demo_password, plans[plan]) for email, plan in MEMBERSHIPS}
    if activated.get(BOOKING[0]):
        log("Запись:")
        ensure_booking(BOOKING[0], demo_password, sessions[BOOKING[1]])

    log("")
    log("Готово. Интерфейс: http://localhost:8080")
    log("Demo-пользователи: " + ", ".join(e for e, _ in TRAINERS + CLIENTS))
    log("Их общий пароль — в файле secrets/demo_users_password; пароль администратора — "
        "в secrets/auth_initial_admin_password. Значения паролей здесь не выводятся.")


if __name__ == "__main__":
    try:
        main()
    except ApiError as error:
        sys.exit("Ошибка API: " + str(error))
