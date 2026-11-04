// Клиент API FitClub. Все запросы идут на тот же origin (/api → Nginx → Gateway).
// Токены хранятся ТОЛЬКО в памяти вкладки: не в localStorage, cookie или URL. После перезагрузки нужен вход.

const tokens = { access: null, refresh: null };
let currentUser = null;
let refreshInFlight = null;
let onSessionLost = () => {};

export class ApiError extends Error {
    constructor(status, problem) {
        super(problem?.detail || problem?.title || 'Ошибка запроса');
        this.status = status;
        this.code = problem?.code || (status === 0 ? 'NETWORK' : 'UNKNOWN');
        this.title = problem?.title;
        this.requestId = problem?.request_id;
        this.fieldErrors = problem?.field_errors || [];
    }
}

// Понятные пользователю причины для стабильных кодов ошибок API
const FRIENDLY = {
    NETWORK: 'Нет связи с сервером. Проверьте подключение и повторите.',
    INVALID_CREDENTIALS: 'Неверный email или пароль.',
    EMAIL_ALREADY_USED: 'Пользователь с таким email уже зарегистрирован.',
    WEAK_PASSWORD: 'Пароль должен содержать не менее 8 символов.',
    PASSWORD_TOO_LONG: 'Пароль слишком длинный.',
    MEMBERSHIP_NOT_VALID: 'Нет действующего абонемента на дату занятия.',
    VISIT_LIMIT_REACHED: 'Посещения по абонементу закончились.',
    SESSION_FULL: 'Мест нет.',
    ALREADY_BOOKED: 'Вы уже записаны на это занятие.',
    SESSION_ALREADY_STARTED: 'Занятие уже началось.',
    SESSION_CANCELLED: 'Занятие отменено.',
    SESSION_NOT_READY: 'Запись на занятие ещё открывается. Повторите через несколько секунд.',
    CANCELLATION_WINDOW_CLOSED: 'Отмена уже недоступна: до начала занятия осталось слишком мало времени.',
    PENDING_ORDER_EXISTS: 'У вас уже есть неоплаченный заказ другого тарифа.',
    ACTIVE_MEMBERSHIP_EXISTS: 'У вас уже есть действующий абонемент.',
    PLAN_NOT_AVAILABLE: 'Тариф больше не продаётся.',
    ORDER_NOT_PAYABLE: 'Этот заказ уже нельзя оплатить. Оформите новый заказ.',
    PAYMENT_ALREADY_COMPLETED: 'Платёж уже завершён.',
    ACCESS_DENIED: 'Недостаточно прав для этого действия.',
    RESOURCE_NOT_FOUND: 'Не найдено.',
    DEPENDENCY_UNAVAILABLE: 'Сервис временно недоступен. Повторите попытку позже.',
    DEPENDENCY_TIMEOUT: 'Сервис не ответил вовремя. Повторите попытку позже.',
    DEPENDENCY_BAD_RESPONSE: 'Сервис временно работает с ошибками. Повторите попытку позже.',
    DATABASE_UNAVAILABLE: 'Сервис временно недоступен. Повторите попытку позже.',
    INTERNAL_ERROR: 'Произошла непредвиденная ошибка.',
};

export function describe(error) {
    if (!(error instanceof ApiError)) {
        return 'Непредвиденная ошибка в интерфейсе.';
    }
    let text = FRIENDLY[error.code] || error.message;
    if (error.code === 'VALIDATION_ERROR' && error.fieldErrors.length) {
        text = 'Проверьте поля: ' + error.fieldErrors.map(f => f.field + ' — ' + f.message).join('; ');
    }
    // Для сбоев сервера показываем код запроса: по нему администратор найдёт запись в логах
    if (error.status >= 500 && error.requestId) {
        text += ' Код запроса: ' + error.requestId;
    }
    return text;
}

export function setSessionLostHandler(handler) {
    onSessionLost = handler;
}

export const session = {
    get user() {
        return currentUser;
    },
    isLoggedIn() {
        return tokens.access !== null;
    },
    hasRole(role) {
        return !!currentUser && currentUser.roles.includes(role);
    },
};

function remember(tokenResponse) {
    tokens.access = tokenResponse.access_token;
    tokens.refresh = tokenResponse.refresh_token;
    currentUser = tokenResponse.user;
}

function forget() {
    tokens.access = null;
    tokens.refresh = null;
    currentUser = null;
}

async function parse(response) {
    const text = await response.text();
    if (!text) {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch {
        return { detail: text };
    }
}

async function refreshTokens() {
    if (!tokens.refresh) {
        return false;
    }
    // Один refresh на все параллельные запросы: ротация делает старый refresh-токен недействительным
    refreshInFlight ??= (async () => {
        try {
            const response = await fetch('/api/auth/refresh', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify({ refresh_token: tokens.refresh }),
            });
            if (!response.ok) {
                return false;
            }
            remember(await response.json());
            return true;
        } catch {
            return false;
        } finally {
            setTimeout(() => { refreshInFlight = null; }, 0);
        }
    })();
    return refreshInFlight;
}

/**
 * @param {string} method
 * @param {string} path            относительный путь /api/...
 * @param {object} [options]
 * @param {object} [options.body]
 * @param {string} [options.idempotencyKey]
 * @param {boolean} [options.auth] отправлять ли access-токен (по умолчанию да)
 */
export async function request(method, path, options = {}) {
    const { body, idempotencyKey, auth = true, retried = false } = options;
    const headers = { Accept: 'application/json' };
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    if (auth && tokens.access) {
        headers.Authorization = 'Bearer ' + tokens.access;
    }
    if (idempotencyKey) {
        headers['Idempotency-Key'] = idempotencyKey;
    }
    let response;
    try {
        response = await fetch(path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            credentials: 'omit',
            cache: 'no-store',
        });
    } catch {
        throw new ApiError(0, null);
    }
    if (response.status === 401 && auth && !retried && tokens.refresh) {
        if (await refreshTokens()) {
            return request(method, path, { ...options, retried: true });
        }
        forget();
        onSessionLost();
    }
    if (response.status === 204) {
        return null;
    }
    const data = await parse(response);
    if (!response.ok) {
        throw new ApiError(response.status, data);
    }
    return data;
}

export const get = (path) => request('GET', path);
export const post = (path, body, idempotencyKey) => request('POST', path, { body, idempotencyKey });
export const patch = (path, body) => request('PATCH', path, { body });

export async function login(email, password) {
    remember(await request('POST', '/api/auth/login', { body: { email, password }, auth: false }));
    return currentUser;
}

export function register(email, password, displayName) {
    return request('POST', '/api/auth/register', {
        body: { email, password, display_name: displayName },
        auth: false,
    });
}

export async function logout() {
    const refresh = tokens.refresh;
    forget();
    if (refresh) {
        try {
            await request('POST', '/api/auth/logout', { body: { refresh_token: refresh }, auth: false });
        } catch {
            // Выход локально уже выполнен; refresh-токен истечёт сам
        }
    }
}

// ---- Idempotency-Key ----
// Один ключ на одно намерение пользователя. Если ответ не пришёл (сеть), ключ сохраняется,
// и повторное нажатие отправит тот же ключ: сервер вернёт результат первой попытки, а не создаст дубль.
const pendingKeys = new Map();

export function uuid() {
    if (globalThis.crypto?.randomUUID) {
        return crypto.randomUUID();
    }
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map(x => x.toString(16).padStart(2, '0')).join('');
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export async function withIdempotency(intent, call) {
    if (!pendingKeys.has(intent)) {
        pendingKeys.set(intent, uuid());
    }
    try {
        const result = await call(pendingKeys.get(intent));
        pendingKeys.delete(intent);
        return result;
    } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 0) {
            pendingKeys.delete(intent);
        }
        throw error;
    }
}
