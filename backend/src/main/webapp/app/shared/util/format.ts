import { ZONE } from 'app/config/constants';

/** Prices are stored in minor units, because floating point money is a bug waiting. */
export const formatPrice = (minor: number, currency: string): string =>
  new Intl.NumberFormat('sv-SE', { style: 'currency', currency, maximumFractionDigits: 0 })
    .format(minor / 100);

/**
 * Formats an instant as a time in the salon's zone, not the browser's.
 *
 * A customer in another timezone booking a salon in Stockholm wants to see the
 * salon's clock; showing theirs would be technically defensible and practically
 * useless.
 */
export const formatTime = (iso: string): string =>
  new Intl.DateTimeFormat('sv-SE', { hour: '2-digit', minute: '2-digit', timeZone: ZONE })
    .format(new Date(iso));

export const formatDay = (iso: string): string =>
  new Intl.DateTimeFormat('sv-SE', { weekday: 'long', day: 'numeric', month: 'long', timeZone: ZONE })
    .format(new Date(iso));

export const formatDistance = (metres: number): string =>
  metres < 1000 ? `${metres} m` : `${(metres / 1000).toFixed(1)} km`;

/** Today in the salon's zone, as the API wants it. */
export const todayInZone = (): string =>
  new Intl.DateTimeFormat('sv-SE', { timeZone: ZONE }).format(new Date());

export const addDays = (day: string, days: number): string => {
  const date = new Date(`${day}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
};
