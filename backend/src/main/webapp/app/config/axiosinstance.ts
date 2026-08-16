import axios from 'axios';
import { API_BASE } from 'app/config/constants';

/**
 * One axios instance for the whole app.
 *
 * Given a base URL rather than each call spelling out `/api`, so that moving
 * the API is one edit. The timeout is deliberate: a booking request that hangs
 * leaves the customer staring at a spinner while a real slot is held for them,
 * and failing visibly is better than that.
 */
const instance = axios.create({
  baseURL: API_BASE,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
});

export default instance;
