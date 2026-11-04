// Интерфейс FitClub: экраны клиента, тренера и администратора. Все данные — через Gateway (/api).
import * as api from './api.js';
import { session, withIdempotency, describe, ApiError } from './api.js';
import { h, mount, toast, busy, empty, loading, failure, pill, load } from './dom.js';
import {
    fmtDateTime, fmtDay, fmtTime, fmtDate, dayKey, dayStartUtc, clubLocalToUtc, todayKey, money,
} from './time.js';

const view = document.getElementById('view');
const nav = document.getElementById('nav');
const sessionBox = document.getElementById('session');
const whoami = document.getElementById('whoami');
const unreadBadge = document.getElementById('unread');

// Номер текущего экрана: фоновые операции (опрос активации) прекращаются, когда пользователь ушёл с экрана
let generation = 0;
let badgeTimer = null;

const ROLE_NAMES = { CLIENT: 'клиент', TRAINER: 'тренер', ADMIN: 'администратор' };
const SESSION_STATUS = {
    DRAFT: ['черновик', 'warn'], PUBLISHED: ['опубликовано', 'ok'], CANCELLED: ['отменено', 'bad'],
};
const MEMBERSHIP_STATUS = { UPCOMING: ['начнётся позже', 'info'], ACTIVE: ['действует', 'ok'], EXPIRED: ['истёк', ''] };

const field = (label, input) => h('label', {}, label, input);
const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== ''));

// ---------------------------------------------------------------- маршруты

const ROUTES = {
    schedule: { title: 'Расписание', render: scheduleView },
    plans: { title: 'Тарифы', role: 'CLIENT', render: plansView },
    membership: { title: 'Мой абонемент', role: 'CLIENT', render: membershipView },
    bookings: { title: 'Мои записи', role: 'CLIENT', render: bookingsView },
    notifications: { title: 'Уведомления', render: notificationsView, hidden: true },
    trainer: { title: 'Мои занятия', role: 'TRAINER', render: trainerView },
    'admin-sessions': { title: 'Занятия', role: 'ADMIN', render: adminSessionsView, admin: true },
    'admin-plans': { title: 'Тарифы клуба', role: 'ADMIN', render: adminPlansView, admin: true },
    'admin-users': { title: 'Пользователи', role: 'ADMIN', render: adminUsersView, admin: true },
};

function allowed(route) {
    return !route.role || session.hasRole(route.role);
}

function currentRoute() {
    const name = location.hash.replace(/^#\/?/, '');
    return ROUTES[name] && allowed(ROUTES[name]) ? name : 'schedule';
}

export function go(name) {
    if (location.hash === '#/' + name) {
        render();
    } else {
        location.hash = '#/' + name;
    }
}

function renderNav(active) {
    const items = [];
    let separated = false;
    for (const [name, route] of Object.entries(ROUTES)) {
        if (route.hidden || !allowed(route)) {
            continue;
        }
        if (route.admin && !separated) {
            items.push(h('span', { class: 'sep' }));
            separated = true;
        }
        items.push(h('button', {
            type: 'button', class: name === active ? 'active' : '', onclick: () => go(name),
        }, route.title));
    }
    mount(nav, items);
}

function render() {
    generation++;
    if (!session.isLoggedIn()) {
        nav.hidden = true;
        sessionBox.hidden = true;
        stopBadge();
        authView();
        return;
    }
    const name = currentRoute();
    nav.hidden = false;
    sessionBox.hidden = false;
    const user = session.user;
    whoami.textContent = user.display_name + ' · ' + user.roles.map(r => ROLE_NAMES[r] || r).join(', ');
    renderNav(name);
    ROUTES[name].render(generation);
}

const stillHere = (gen) => gen === generation;

// ---------------------------------------------------------------- вход и регистрация

function authView() {
    const loginForm = h('form', { class: 'card stack' },
        h('h1', {}, 'Вход'),
        field('Email', h('input', { name: 'email', type: 'email', required: true, autocomplete: 'username' })),
        field('Пароль', h('input', {
            name: 'password', type: 'password', required: true, autocomplete: 'current-password',
        })),
        h('button', { class: 'primary', type: 'submit' }, 'Войти'));
    const registerForm = h('form', { class: 'card stack' },
        h('h2', {}, 'Регистрация'),
        h('p', { class: 'muted small' }, 'Новый пользователь получает роль клиента.'),
        field('Имя', h('input', { name: 'display_name', required: true, maxlength: 100, autocomplete: 'name' })),
        field('Email', h('input', { name: 'email', type: 'email', required: true, autocomplete: 'email' })),
        field('Пароль (не короче 8 символов)', h('input', {
            name: 'password', type: 'password', required: true, minlength: 8, autocomplete: 'new-password',
        })),
        h('button', { class: 'secondary', type: 'submit' }, 'Зарегистрироваться'));

    loginForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const f = loginForm.elements;
        busy(loginForm.querySelector('button'), async () => {
            await api.login(f.email.value.trim(), f.password.value);
            f.password.value = '';
            startBadge();
            go(location.hash ? currentRoute() : 'schedule');
        });
    });
    registerForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const f = registerForm.elements;
        busy(registerForm.querySelector('button'), async () => {
            const email = f.email.value.trim();
            const password = f.password.value;
            await api.register(email, password, f.display_name.value.trim());
            f.password.value = '';
            await api.login(email, password);
            toast('Добро пожаловать! Выберите тариф, чтобы записываться на занятия.');
            startBadge();
            go('plans');
        });
    });
    mount(view, h('div', { class: 'auth' }, loginForm, registerForm));
}

// ---------------------------------------------------------------- расписание

async function scheduleView(gen) {
    const isClient = session.hasRole('CLIENT');
    mount(view,
        h('h1', {}, 'Расписание на 2 недели'),
        h('p', { class: 'muted' }, 'Время — по Кишинёву. Свободные места обновляются при каждом открытии.'),
        h('div', { id: 'schedule-body' }, loading()));
    const body = view.querySelector('#schedule-body');
    try {
        const page = await api.get('/api/sessions?' + qs({
            from: new Date().toISOString(), to: dayStartUtc(15), status: 'PUBLISHED', size: 100,
        }));
        const sessions = page.items;
        const [availability, myBookings] = await Promise.all([
            sessions.length
                ? api.get('/api/availability?' + new URLSearchParams(sessions.map(s => ['session_id', s.id])))
                : [],
            isClient ? api.get('/api/bookings/me?status=CONFIRMED&size=100') : { items: [] },
        ]);
        if (!stillHere(gen)) {
            return;
        }
        const bySession = new Map(availability.map(a => [a.session_id, a]));
        const booked = new Set(myBookings.items.map(b => b.session_id));
        if (!sessions.length) {
            mount(body, empty('Ближайших занятий пока нет.'));
            return;
        }
        const days = new Map();
        for (const s of sessions) {
            const key = dayKey(s.starts_at);
            if (!days.has(key)) {
                days.set(key, []);
            }
            days.get(key).push(s);
        }
        mount(body, [...days.values()].map(list => h('section', {},
            h('h2', {}, capitalize(fmtDay(list[0].starts_at)) + (dayKey(list[0].starts_at) === todayKey() ? ' (сегодня)' : '')),
            h('div', { class: 'grid' }, list.map(s => sessionCard(s, bySession.get(s.id), booked.has(s.id), isClient, gen))))));
    } catch (error) {
        if (stillHere(gen)) {
            mount(body, failure(error));
        }
    }
}

function capitalize(text) {
    return text.charAt(0).toUpperCase() + text.slice(1);
}

function availabilityPill(a) {
    if (!a || a.state === 'NOT_READY') {
        return pill('места уточняются', 'info');
    }
    if (a.state === 'CANCELLED') {
        return pill('отменено', 'bad');
    }
    if (a.available <= 0) {
        return pill('мест нет', 'bad');
    }
    return pill(`свободно ${a.available} из ${a.capacity}`, a.available <= 2 ? 'warn' : 'ok');
}

function sessionCard(s, a, isBooked, isClient, gen) {
    let action = null;
    if (isClient) {
        if (isBooked) {
            action = pill('вы записаны', 'ok');
        } else if (a && a.state === 'OPEN' && a.available > 0) {
            const button = h('button', { class: 'primary', type: 'button' }, 'Записаться');
            button.addEventListener('click', () => busy(button, async () => {
                await withIdempotency('book:' + s.id,
                    key => api.post('/api/bookings', { session_id: s.id }, key));
                toast('Вы записаны на «' + s.title + '».');
                if (stillHere(gen)) {
                    scheduleView(gen);
                }
            }));
            action = button;
        }
    }
    return h('div', { class: 'card stack' },
        h('div', { class: 'row between' },
            h('span', { class: 'strong' }, fmtTime(s.starts_at) + '–' + fmtTime(s.ends_at)),
            availabilityPill(a)),
        h('div', { class: 'strong' }, s.title),
        h('div', { class: 'muted small' }, s.training_type_name + ' · тренер ' + s.trainer_display_name),
        action);
}

// ---------------------------------------------------------------- тарифы и оплата

async function plansView(gen) {
    mount(view, h('h1', {}, 'Тарифы'), h('div', { id: 'plans-body' }, loading()), h('div', { id: 'checkout' }));
    const body = view.querySelector('#plans-body');
    try {
        const [plans, memberships] = await Promise.all([api.get('/api/plans'), api.get('/api/memberships/me')]);
        if (!stillHere(gen)) {
            return;
        }
        const current = memberships.find(m => m.status === 'ACTIVE' || m.status === 'UPCOMING');
        mount(body,
            current && h('div', { class: 'notice info' },
                `У вас уже есть абонемент «${current.plan_name}» до ${fmtDate(current.ends_at)}. `
                + 'Новый можно оформить после его окончания.'),
            plans.length ? h('div', { class: 'grid' }, plans.map(p => planCard(p, !!current, gen)))
                : empty('Тарифы пока не добавлены.'));
    } catch (error) {
        if (stillHere(gen)) {
            mount(body, failure(error));
        }
    }
}

function planCard(plan, hasCurrent, gen) {
    const button = h('button', { class: 'primary', type: 'button', disabled: hasCurrent }, 'Оформить');
    button.addEventListener('click', () => busy(button, async () => {
        const order = await withIdempotency('order:' + plan.id,
            key => api.post('/api/membership-orders', { plan_id: plan.id }, key));
        const payment = await withIdempotency('pay:' + order.id,
            key => api.post('/api/payments', { membership_order_id: order.id }, key));
        if (stillHere(gen)) {
            checkout(order, payment, gen);
        }
    }));
    return h('div', { class: 'card stack' },
        h('div', { class: 'strong' }, plan.name),
        h('div', {}, plan.unlimited ? 'Без ограничения посещений' : `${plan.visit_limit} посещений`,
            ` · ${plan.duration_days} дн.`),
        h('div', { class: 'strong' }, money(plan.price_minor, plan.currency)),
        button);
}

function checkout(order, payment, gen) {
    const box = view.querySelector('#checkout');
    const status = h('div', { class: 'stack' });
    const succeed = h('button', { class: 'primary', type: 'button' }, 'Оплатить (тест: успешно)');
    const decline = h('button', { class: 'danger', type: 'button' }, 'Тест: отклонить платёж');
    const buttons = h('div', { class: 'row' }, succeed, decline);

    const simulate = (button, outcome) => busy(button, async () => {
        let result;
        try {
            result = await withIdempotency(`simulate:${payment.id}:${outcome}`,
                key => api.post(`/api/payments/${payment.id}/simulate`, { outcome }, key));
        } catch (error) {
            if (error instanceof ApiError && error.status === 404) {
                mount(status, h('div', { class: 'notice' },
                    'Тестовая оплата выключена: стенд запущен без demo-профиля (FITCLUB_PROFILES=docker,demo).'));
                buttons.hidden = true;
                return;
            }
            throw error;
        }
        buttons.hidden = true;
        if (result.status === 'SUCCEEDED') {
            await waitForMembership(order, status, gen);
        } else {
            mount(status, h('div', { class: 'notice' },
                'Платёж отклонён (тестовый сценарий). Абонемент не активирован — можно оформить заказ заново.'));
        }
    });
    succeed.addEventListener('click', () => simulate(succeed, 'SUCCESS'));
    decline.addEventListener('click', () => simulate(decline, 'DECLINE'));

    const done = payment.status !== 'PENDING';
    mount(box, h('div', { class: 'card stack' },
        h('h2', {}, 'Оплата: ' + order.plan_name),
        h('div', {}, 'Сумма: ', h('span', { class: 'strong' }, money(payment.amount_minor, payment.currency))),
        h('div', { class: 'notice info' },
            'Это тестовая оплата. Деньги не списываются, данные карты не запрашиваются: '
            + 'кнопки ниже только имитируют ответ платёжной системы.'),
        done ? null : buttons,
        status));
    if (done && payment.status === 'SUCCEEDED') {
        waitForMembership(order, status, gen);
    } else if (done) {
        mount(status, h('div', { class: 'notice' }, 'Этот платёж уже отклонён. Оформите заказ заново.'));
    }
    box.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/** Абонемент активируется асинхронно (событие payment.succeeded) — ждём его ограниченное время. */
async function waitForMembership(order, status, gen) {
    mount(status, h('p', { class: 'strong' }, 'Оплата прошла. Активация абонемента…'));
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline && stillHere(gen)) {
        try {
            const memberships = await api.get('/api/memberships/me');
            const activated = memberships.find(m => m.order_id === order.id);
            if (activated) {
                if (stillHere(gen)) {
                    mount(status, h('div', { class: 'notice info' },
                        `Абонемент «${activated.plan_name}» активирован: ${fmtDate(activated.starts_at)} — `
                        + `${fmtDate(activated.ends_at)}. `,
                        h('button', { class: 'link', type: 'button', onclick: () => go('schedule') },
                            'Перейти к расписанию')));
                    refreshBadge();
                }
                return;
            }
        } catch {
            // временная ошибка — пробуем ещё раз до истечения срока
        }
        await new Promise(resolve => setTimeout(resolve, 1000));
    }
    if (stillHere(gen)) {
        mount(status, h('div', { class: 'notice' },
            'Оплата прошла, абонемент ещё активируется. Загляните в «Мой абонемент» через минуту — '
            + 'повторно платить не нужно.'));
    }
}

// ---------------------------------------------------------------- мой абонемент

function membershipView() {
    mount(view, h('h1', {}, 'Мой абонемент'), h('div', { id: 'm-body' }));
    load(view.querySelector('#m-body'),
        () => Promise.all([api.get('/api/memberships/me'), api.get('/api/bookings/usage/me')]),
        ([memberships, usage]) => {
            if (!memberships.length) {
                return h('div', {}, empty('Абонементов пока нет.'),
                    h('p', {}, h('button', { class: 'primary', type: 'button', onclick: () => go('plans') },
                        'Выбрать тариф')));
            }
            const usageById = new Map(usage.map(u => [u.membership_id, u]));
            return h('div', { class: 'grid' }, memberships.map(m => {
                const [label, kind] = MEMBERSHIP_STATUS[m.status] || [m.status, ''];
                const u = usageById.get(m.id);
                const visits = m.unlimited
                    ? `Безлимит · использовано ${u ? u.used : 0}`
                    : `Осталось ${u ? u.remaining : m.visit_limit} из ${m.visit_limit} посещений`;
                return h('div', { class: 'card stack' },
                    h('div', { class: 'row between' }, h('span', { class: 'strong' }, m.plan_name), pill(label, kind)),
                    h('div', {}, `${fmtDate(m.starts_at)} — ${fmtDate(m.ends_at)}`),
                    h('div', { class: 'muted' }, visits));
            }));
        });
}

// ---------------------------------------------------------------- мои записи

function bookingsView(gen) {
    mount(view, h('h1', {}, 'Мои записи'), h('div', { id: 'b-body' }));
    const body = view.querySelector('#b-body');
    load(body, () => api.get('/api/bookings/me?size=100'), (page) => {
        const upcoming = page.items.filter(b => b.status === 'CONFIRMED' && !b.past);
        const history = page.items.filter(b => !(b.status === 'CONFIRMED' && !b.past));
        upcoming.sort((a, b) => a.starts_at.localeCompare(b.starts_at));
        return h('div', {},
            h('h2', {}, 'Предстоящие'),
            upcoming.length ? bookingTable(upcoming, true, gen) : empty('Нет предстоящих записей.'),
            h('h2', {}, 'История'),
            history.length ? bookingTable(history, false, gen) : empty('История пуста.'));
    });
}

function bookingTable(items, upcoming, gen) {
    return h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Занятие'), h('th', {}, 'Когда'), h('th', {}, 'Статус'),
            h('th', {}, 'Тренер'))),
        h('tbody', {}, items.map(b => h('tr', {},
            h('td', {}, b.session_title),
            h('td', {}, fmtDateTime(b.starts_at)),
            h('td', {}, upcoming ? cancelCell(b, gen) : bookingStatus(b)),
            h('td', {}, b.trainer_display_name)))));
}

function bookingStatus(b) {
    if (b.status === 'CANCELLED') {
        return pill(b.cancel_reason === 'SESSION_CANCELLED' ? 'занятие отменено клубом' : 'отменена вами', 'bad');
    }
    return pill('прошло', '');
}

function cancelCell(b, gen) {
    if (!b.cancellable) {
        return h('span', { class: 'muted small' }, 'Отмена была доступна до ' + fmtDateTime(b.cancellable_until));
    }
    const button = h('button', { class: 'danger', type: 'button' }, 'Отменить запись');
    button.addEventListener('click', () => busy(button, async () => {
        await api.post(`/api/bookings/${b.id}/cancel`);
        toast('Запись отменена, посещение возвращено.');
        if (stillHere(gen)) {
            bookingsView(gen);
        }
    }));
    return h('div', { class: 'stack' }, pill('подтверждена', 'ok'), button,
        h('div', { class: 'muted small' }, 'Отменить можно до ' + fmtDateTime(b.cancellable_until)));
}

// ---------------------------------------------------------------- уведомления

function notificationsView(gen, onlyUnread = false) {
    const toggle = h('input', { type: 'checkbox', checked: onlyUnread });
    toggle.style.width = 'auto';
    toggle.addEventListener('change', () => notificationsView(gen, toggle.checked));
    mount(view,
        h('div', { class: 'row between' }, h('h1', {}, 'Уведомления'),
            h('label', { class: 'row' }, toggle, 'только непрочитанные')),
        h('div', { id: 'n-body' }));
    load(view.querySelector('#n-body'), () => api.get('/api/notifications?' + qs({ unread: onlyUnread, size: 50 })),
        (page) => page.items.length
            ? h('div', {}, page.items.map(n => notificationCard(n, gen, onlyUnread)))
            : empty(onlyUnread ? 'Непрочитанных уведомлений нет.' : 'Уведомлений пока нет.'));
}

function notificationCard(n, gen, onlyUnread) {
    let action = null;
    if (!n.read) {
        action = h('button', { class: 'link', type: 'button' }, 'Отметить прочитанным');
        action.addEventListener('click', () => busy(action, async () => {
            await api.patch(`/api/notifications/${n.id}/read`);
            refreshBadge();
            if (stillHere(gen)) {
                notificationsView(gen, onlyUnread);
            }
        }));
    }
    return h('div', { class: 'card stack' + (n.read ? '' : ' unread') },
        h('div', { class: 'row between' }, h('span', { class: 'strong' }, n.title),
            h('span', { class: 'muted small' }, fmtDateTime(n.created_at))),
        h('div', {}, n.body),
        action);
}

function startBadge() {
    stopBadge();
    refreshBadge();
    // Умеренный опрос: раз в 30 секунд и только пока вкладка видима
    badgeTimer = setInterval(() => {
        if (!document.hidden) {
            refreshBadge();
        }
    }, 30000);
}

function stopBadge() {
    clearInterval(badgeTimer);
    badgeTimer = null;
    unreadBadge.hidden = true;
}

async function refreshBadge() {
    if (!session.isLoggedIn()) {
        return;
    }
    try {
        const { unread } = await api.get('/api/notifications/unread-count');
        unreadBadge.textContent = unread > 99 ? '99+' : String(unread);
        unreadBadge.hidden = unread === 0;
    } catch {
        // счётчик не критичен: при сбое Notification остальной интерфейс работает
    }
}

// ---------------------------------------------------------------- тренер

function trainerView() {
    mount(view, h('h1', {}, 'Мои занятия'),
        h('p', { class: 'muted' }, 'Занятия, где вы назначены тренером: прошедшие за неделю и будущие.'),
        h('div', { id: 't-body' }));
    load(view.querySelector('#t-body'),
        () => api.get('/api/sessions/mine?' + qs({ from: dayStartUtc(-7), size: 100 })),
        (page) => {
            if (!page.items.length) {
                return empty('Вам пока не назначены занятия.');
            }
            return h('div', {}, page.items.map(s => {
                const [label, kind] = SESSION_STATUS[s.status];
                const participants = h('div', {});
                const button = h('button', { class: 'secondary', type: 'button' }, 'Участники');
                button.addEventListener('click', () => busy(button, async () => {
                    const list = await api.get(`/api/sessions/${s.id}/bookings`);
                    mount(participants, list.participants.length
                        ? h('table', {}, h('tbody', {}, list.participants.map((p, i) => h('tr', {},
                            h('td', {}, String(i + 1)), h('td', {}, p.display_name),
                            h('td', { class: 'muted small' }, 'записался ' + fmtDateTime(p.booked_at))))))
                        : empty('Пока никто не записался.'));
                }));
                return h('div', { class: 'card stack' },
                    h('div', { class: 'row between' },
                        h('span', { class: 'strong' }, s.title + ' · ' + fmtDateTime(s.starts_at)), pill(label, kind)),
                    h('div', { class: 'muted small' }, `${s.training_type_name} · мест ${s.capacity}`),
                    s.status === 'CANCELLED' ? null : button,
                    participants);
            }));
        });
}

// ---------------------------------------------------------------- администратор: тарифы

function adminPlansView(gen) {
    const form = h('form', { class: 'card stack' },
        h('h2', {}, 'Новый тариф'),
        h('div', { class: 'inline' },
            field('Название', h('input', { name: 'name', required: true, maxlength: 100 })),
            field('Дней', h('input', { name: 'duration_days', type: 'number', min: 1, max: 3660, value: 30, required: true })),
            field('Посещений (пусто — безлимит)', h('input', { name: 'visit_limit', type: 'number', min: 1 })),
            field('Цена, MDL', h('input', { name: 'price', inputmode: 'decimal', required: true, placeholder: '450.00' }))),
        h('button', { class: 'primary', type: 'submit' }, 'Добавить тариф'));
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const f = form.elements;
        const price = Math.round(parseFloat(f.price.value.replace(',', '.')) * 100);
        if (!Number.isFinite(price) || price < 0) {
            toast('Укажите цену числом, например 450 или 450.50.', 'error');
            return;
        }
        busy(form.querySelector('button'), async () => {
            await api.post('/api/plans', {
                name: f.name.value.trim(),
                duration_days: Number(f.duration_days.value),
                visit_limit: f.visit_limit.value ? Number(f.visit_limit.value) : null,
                price_minor: price,
                currency: 'MDL',
            });
            toast('Тариф добавлен.');
            if (stillHere(gen)) {
                adminPlansView(gen);
            }
        });
    });
    mount(view, h('h1', {}, 'Тарифы клуба'), h('div', { id: 'ap-body' }), form);
    load(view.querySelector('#ap-body'), () => api.get('/api/plans?include_inactive=true'), (plans) => plans.length
        ? h('table', {},
            h('thead', {}, h('tr', {}, h('th', {}, 'Тариф'), h('th', {}, 'Условия'), h('th', {}, 'Продажа'),
                h('th', {}, 'Цена'))),
            h('tbody', {}, plans.map(p => {
                const toggle = h('button', { class: p.active ? 'danger' : 'secondary', type: 'button' },
                    p.active ? 'Снять с продажи' : 'Вернуть в продажу');
                toggle.addEventListener('click', () => busy(toggle, async () => {
                    await api.patch(`/api/plans/${p.id}`, { active: !p.active });
                    if (stillHere(gen)) {
                        adminPlansView(gen);
                    }
                }));
                return h('tr', {},
                    h('td', { class: 'strong' }, p.name),
                    h('td', {}, (p.unlimited ? 'безлимит' : p.visit_limit + ' посещ.') + ' · ' + p.duration_days + ' дн.'),
                    h('td', { class: 'stack' }, pill(p.active ? 'продаётся' : 'снят', p.active ? 'ok' : ''), toggle),
                    h('td', {}, money(p.price_minor, p.currency)));
            })))
        : empty('Тарифов нет — добавьте первый.'));
}

// ---------------------------------------------------------------- администратор: типы и занятия

async function adminSessionsView(gen) {
    mount(view, h('h1', {}, 'Занятия'), loading());
    let types;
    let trainers;
    try {
        [types, trainers] = await Promise.all([
            api.get('/api/training-types'),
            api.get('/api/admin/users?role=TRAINER&size=100'),
        ]);
    } catch (error) {
        mount(view, h('h1', {}, 'Занятия'), failure(error));
        return;
    }
    if (!stillHere(gen)) {
        return;
    }
    const list = h('div', {});
    mount(view,
        h('h1', {}, 'Занятия'),
        h('p', { class: 'muted' },
            'Новое занятие создаётся черновиком. После публикации время, тренер и вместимость зафиксированы — '
            + 'занятие можно только отменить (записи отменятся, посещения вернутся клиентам).'),
        sessionForm(types, trainers.items, gen),
        h('h2', {}, 'Ближайшие 30 дней'),
        list,
        typeForm(types, gen));
    load(list, () => api.get('/api/sessions?' + qs({ from: dayStartUtc(-1), to: dayStartUtc(31), size: 100 })),
        (page) => page.items.length
            ? h('div', {}, page.items.map(s => adminSessionCard(s, types, trainers.items, gen)))
            : empty('Занятий нет.'));
}

function typeForm(types, gen) {
    const form = h('form', { class: 'card stack' },
        h('h2', {}, 'Типы тренировок'),
        h('p', { class: 'muted small' }, types.length ? types.map(t => t.name).join(' · ') : 'Типов пока нет.'),
        h('div', { class: 'inline' },
            field('Название', h('input', { name: 'name', required: true, maxlength: 100 })),
            field('Описание', h('input', { name: 'description', maxlength: 1000 }))),
        h('button', { class: 'secondary', type: 'submit' }, 'Добавить тип'));
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        busy(form.querySelector('button'), async () => {
            await api.post('/api/training-types', {
                name: form.elements.name.value.trim(),
                description: form.elements.description.value.trim() || null,
            });
            toast('Тип тренировки добавлен.');
            if (stillHere(gen)) {
                adminSessionsView(gen);
            }
        });
    });
    return form;
}

function sessionFields(types, trainers, s) {
    const tomorrow = dayStartUtc(1);
    return h('div', { class: 'inline' },
        field('Тип', h('select', { name: 'training_type_id', required: true },
            types.map(t => h('option', { value: t.id, selected: s && s.training_type_id === t.id }, t.name)))),
        field('Тренер', h('select', { name: 'trainer_id', required: true },
            trainers.map(t => h('option', { value: t.id, selected: s && s.trainer_id === t.id }, t.display_name)))),
        field('Название', h('input', { name: 'title', required: true, maxlength: 150, value: s?.title })),
        field('Дата', h('input', { name: 'date', type: 'date', required: true, value: dayKey(s ? s.starts_at : tomorrow) })),
        field('Начало', h('input', { name: 'start', type: 'time', required: true, value: s ? fmtTime(s.starts_at) : '09:00' })),
        field('Конец', h('input', { name: 'end', type: 'time', required: true, value: s ? fmtTime(s.ends_at) : '10:00' })),
        field('Мест', h('input', { name: 'capacity', type: 'number', min: 1, max: 500, required: true, value: s ? s.capacity : 10 })));
}

function readSessionFields(form) {
    const f = form.elements;
    return {
        training_type_id: f.training_type_id.value,
        trainer_id: f.trainer_id.value,
        title: f.title.value.trim(),
        starts_at: clubLocalToUtc(f.date.value, f.start.value),
        ends_at: clubLocalToUtc(f.date.value, f.end.value),
        capacity: Number(f.capacity.value),
    };
}

function sessionForm(types, trainers, gen) {
    if (!types.length || !trainers.length) {
        return h('div', { class: 'notice' }, !types.length
            ? 'Сначала добавьте тип тренировки (форма внизу страницы).'
            : 'Нет пользователей с ролью тренера. Назначьте роль в разделе «Пользователи».');
    }
    const form = h('form', { class: 'card stack' }, h('h2', {}, 'Новое занятие (черновик)'),
        sessionFields(types, trainers, null), h('button', { class: 'primary', type: 'submit' }, 'Создать черновик'));
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        busy(form.querySelector('button'), async () => {
            await api.post('/api/sessions', readSessionFields(form));
            toast('Черновик создан. Проверьте и опубликуйте его.');
            if (stillHere(gen)) {
                adminSessionsView(gen);
            }
        });
    });
    return form;
}

function adminSessionCard(s, types, trainers, gen) {
    const [label, kind] = SESSION_STATUS[s.status];
    const extra = h('div', {});
    const actions = h('div', { class: 'row' });
    const reload = () => stillHere(gen) && adminSessionsView(gen);

    if (s.status === 'DRAFT') {
        const publish = h('button', { class: 'primary', type: 'button' }, 'Опубликовать');
        publish.addEventListener('click', () => busy(publish, async () => {
            await api.post(`/api/sessions/${s.id}/publish`);
            toast('Занятие опубликовано, запись открывается.');
            reload();
        }));
        const edit = h('button', { class: 'secondary', type: 'button' }, 'Изменить');
        edit.addEventListener('click', () => {
            const form = h('form', { class: 'stack' }, sessionFields(types, trainers, s),
                h('button', { class: 'primary', type: 'submit' }, 'Сохранить'));
            form.addEventListener('submit', (event) => {
                event.preventDefault();
                busy(form.querySelector('button'), async () => {
                    await api.patch(`/api/sessions/${s.id}`, readSessionFields(form));
                    toast('Черновик сохранён.');
                    reload();
                });
            });
            mount(extra, form);
        });
        actions.append(publish, edit);
    }
    if (s.status !== 'CANCELLED') {
        const cancel = h('button', { class: 'danger', type: 'button' }, 'Отменить занятие');
        cancel.addEventListener('click', () => {
            const reason = h('input', { maxlength: 500, placeholder: 'Причина (увидят записавшиеся)' });
            const confirm = h('button', { class: 'danger', type: 'button' }, 'Подтвердить отмену');
            confirm.addEventListener('click', () => busy(confirm, async () => {
                await api.post(`/api/sessions/${s.id}/cancel`, { reason: reason.value.trim() || null });
                toast('Занятие отменено. Записи будут отменены, клиенты получат уведомления.');
                reload();
            }));
            mount(extra, h('div', { class: 'inline' }, field('Причина', reason), confirm));
        });
        actions.append(cancel);
    }
    return h('div', { class: 'card stack' },
        h('div', { class: 'row between' },
            h('span', { class: 'strong' }, s.title + ' · ' + fmtDateTime(s.starts_at) + '–' + fmtTime(s.ends_at)),
            h('span', { class: 'row' }, pill(label, kind),
                s.status === 'PUBLISHED' ? pill('параметры зафиксированы', 'info') : null)),
        h('div', { class: 'muted small' },
            `${s.training_type_name} · тренер ${s.trainer_display_name} · мест ${s.capacity}`
            + (s.cancel_reason ? ' · причина отмены: ' + s.cancel_reason : '')),
        actions,
        extra);
}

// ---------------------------------------------------------------- администратор: пользователи

function adminUsersView(gen, query = '', role = '') {
    const search = h('input', { type: 'search', placeholder: 'email или имя', value: query, maxlength: 100 });
    const roleSelect = h('select', {}, h('option', { value: '' }, 'все роли'),
        Object.entries(ROLE_NAMES).map(([r, n]) => h('option', { value: r, selected: r === role }, n)));
    const form = h('form', { class: 'inline' }, field('Поиск', search), field('Роль', roleSelect),
        h('button', { class: 'secondary', type: 'submit' }, 'Найти'));
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        adminUsersView(gen, search.value.trim(), roleSelect.value);
    });
    mount(view, h('h1', {}, 'Пользователи'), form, h('div', { id: 'u-body', class: 'stack' }));
    load(view.querySelector('#u-body'), () => api.get('/api/admin/users?' + qs({ q: query, role, size: 50 })),
        (page) => page.items.length
            ? h('table', {},
                h('thead', {}, h('tr', {}, h('th', {}, 'Пользователь'), h('th', {}, 'Роли'), h('th', {}, ''))),
                h('tbody', {}, page.items.map(u => userRow(u))))
            : empty('Никого не найдено.'));
}

function userRow(user) {
    const boxes = Object.keys(ROLE_NAMES).map(r => {
        const box = h('input', { type: 'checkbox', value: r, checked: user.roles.includes(r) });
        box.style.width = 'auto';
        return h('label', { class: 'row' }, box, ROLE_NAMES[r]);
    });
    const save = h('button', { class: 'secondary', type: 'button' }, 'Сохранить роли');
    save.addEventListener('click', () => {
        const roles = boxes.map(l => l.querySelector('input')).filter(b => b.checked).map(b => b.value);
        if (!roles.length) {
            toast('Оставьте хотя бы одну роль.', 'error');
            return;
        }
        busy(save, () => api.patch(`/api/admin/users/${user.id}/roles`, { roles }), { success: 'Роли обновлены.' });
    });
    return h('tr', {},
        h('td', {}, h('div', { class: 'strong' }, user.display_name), h('div', { class: 'muted small' }, user.email)),
        h('td', {}, h('div', { class: 'row' }, boxes)),
        h('td', {}, save));
}

// ---------------------------------------------------------------- запуск

document.getElementById('logout').addEventListener('click', async () => {
    await api.logout();
    location.hash = '';
    render();
});
document.getElementById('bell').addEventListener('click', () => go('notifications'));
api.setSessionLostHandler(() => {
    toast('Сессия истекла. Войдите снова.', 'error');
    render();
});
window.addEventListener('hashchange', render);
window.addEventListener('unhandledrejection', (event) => {
    toast(describe(event.reason), 'error');
});
render();
