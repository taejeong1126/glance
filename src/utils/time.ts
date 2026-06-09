const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

function toKstDate(timestampMillis: number) {
    return new Date(timestampMillis + KST_OFFSET_MS);
}

export function formatKstClock(timestampMillis: number) {
    const date = toKstDate(timestampMillis);
    const hours = date.getUTCHours().toString().padStart(2, '0');
    const minutes = date.getUTCMinutes().toString().padStart(2, '0');

    return `${hours}:${minutes}`;
}

export function formatKstUpdatedAt(timestampMillis: number) {
    const date = toKstDate(timestampMillis);
    const month = date.getUTCMonth() + 1;
    const day = date.getUTCDate();
    const hours = date.getUTCHours();
    const period = hours < 12 ? '오전' : '오후';
    const displayHours = hours % 12 || 12;
    const minutes = date.getUTCMinutes().toString().padStart(2, '0');

    return `${month}월 ${day}일 ${period} ${displayHours}시 ${minutes}분`;
}
