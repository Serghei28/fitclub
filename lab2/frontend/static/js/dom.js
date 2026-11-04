// Небольшие помощники DOM. Данные из API вставляются только как текст (textContent), не как HTML —
// так содержимое (например, имя пользователя) не может выполниться как разметка или скрипт.
import { describe } from './api.js';

export function h(tag, attrs = {}, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
        if (value === undefined || value === null || value === false) {
            continue;
        }
        if (key === 'class') {
            el.className = value;
        } else if (key.startsWith('on') && typeof value === 'function') {
            el.addEventListener(key.slice(2), value);
        } else if (value === true) {
            el.setAttribute(key, '');
        } else {
            el.setAttribute(key, value);
        }
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) {
            continue;
        }
        el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
}

export function mount(container, ...children) {
    container.replaceChildren(...children.flat().filter(Boolean));
}

let toastTimer;

export function toast(message, kind = 'info') {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = 'toast' + (kind === 'error' ? ' error' : '');
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, kind === 'error' ? 7000 : 3500);
}

/**
 * Выполнить действие с блокировкой кнопки на время запроса: двойное нажатие не отправит второй запрос.
 * Ошибка показывается пользователю понятным текстом.
 */
export async function busy(button, action, { success } = {}) {
    if (button.disabled) {
        return undefined;
    }
    const label = button.textContent;
    button.disabled = true;
    button.textContent = 'Подождите…';
    try {
        const result = await action();
        if (success) {
            toast(success);
        }
        return result;
    } catch (error) {
        toast(describe(error), 'error');
        return undefined;
    } finally {
        button.disabled = false;
        button.textContent = label;
    }
}

export const empty = (text) => h('div', { class: 'empty' }, text);
export const loading = () => h('p', { class: 'muted' }, 'Загрузка…');
export const failure = (error) => h('div', { class: 'notice' }, describe(error));

export function pill(text, kind = '') {
    return h('span', { class: 'pill ' + kind }, text);
}

/** Загрузить данные и отрисовать: показывает «Загрузка…», затем результат или понятную ошибку. */
export async function load(container, fetcher, render) {
    mount(container, loading());
    try {
        const data = await fetcher();
        mount(container, render(data));
    } catch (error) {
        mount(container, failure(error));
    }
}
