// Время в API — UTC (ISO 8601), в интерфейсе — часовой пояс клуба.
export const ZONE = 'Europe/Chisinau';

const dateTimeFormat = new Intl.DateTimeFormat('ru-RU', {
    timeZone: ZONE, weekday: 'short', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit',
});
const dayFormat = new Intl.DateTimeFormat('ru-RU', { timeZone: ZONE, weekday: 'long', day: 'numeric', month: 'long' });
const timeFormat = new Intl.DateTimeFormat('ru-RU', { timeZone: ZONE, hour: '2-digit', minute: '2-digit' });
const dateFormat = new Intl.DateTimeFormat('ru-RU', { timeZone: ZONE, day: '2-digit', month: '2-digit', year: 'numeric' });
const partsFormat = new Intl.DateTimeFormat('en-US', {
    timeZone: ZONE, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
});

export const fmtDateTime = (iso) => iso ? dateTimeFormat.format(new Date(iso)) : '—';
export const fmtDay = (iso) => dayFormat.format(new Date(iso));
export const fmtTime = (iso) => timeFormat.format(new Date(iso));
export const fmtDate = (iso) => iso ? dateFormat.format(new Date(iso)) : '—';

function zoneParts(epochMs) {
    const p = Object.fromEntries(partsFormat.formatToParts(new Date(epochMs)).map(x => [x.type, x.value]));
    return { y: +p.year, m: +p.month, d: +p.day, h: +p.hour, min: +p.minute, s: +p.second };
}

/** Смещение часового пояса клуба от UTC в минутах для данного момента (учитывает летнее время). */
function offsetMinutes(epochMs) {
    const z = zoneParts(epochMs);
    const asUtc = Date.UTC(z.y, z.m - 1, z.d, z.h, z.min, z.s);
    return Math.round((asUtc - Math.floor(epochMs / 1000) * 1000) / 60000);
}

/** Ключ дня в поясе клуба: YYYY-MM-DD. */
export function dayKey(iso) {
    const z = zoneParts(new Date(iso).getTime());
    return `${z.y}-${String(z.m).padStart(2, '0')}-${String(z.d).padStart(2, '0')}`;
}

export const todayKey = () => dayKey(new Date().toISOString());

/** Дата и время, введённые по времени клуба ('2026-11-10', '09:00') → ISO в UTC. */
export function clubLocalToUtc(date, time) {
    const [y, m, d] = date.split('-').map(Number);
    const [h, min] = time.split(':').map(Number);
    const guess = Date.UTC(y, m - 1, d, h, min);
    let result = guess - offsetMinutes(guess) * 60000;
    const corrected = guess - offsetMinutes(result) * 60000;
    if (corrected !== result) {
        result = corrected;
    }
    return new Date(result).toISOString();
}

/** Начало дня (00:00 по клубу) через n дней от сегодняшнего, в UTC ISO. */
export function dayStartUtc(daysFromToday) {
    const [y, m, d] = todayKey().split('-').map(Number);
    const target = new Date(Date.UTC(y, m - 1, d + daysFromToday));
    const key = target.toISOString().slice(0, 10);
    return clubLocalToUtc(key, '00:00');
}

export function money(minor, currency) {
    return (minor / 100).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
        + ' ' + currency;
}
