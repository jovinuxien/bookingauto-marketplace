/** Relative on purpose: the SPA is served by the backend, so there is no host to configure. */
export const API_BASE = '/api';

/** Cal computes day boundaries here, and so must anything that formats a slot. */
export const ZONE = 'Europe/Stockholm';

export const DEFAULT_RADIUS_METRES = 5000;

/** Stockholm. Used until the browser offers a real position. */
export const FALLBACK_POSITION = { lat: 59.3293, lon: 18.0686 };
