import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { BookingOutcome } from 'app/shared/model/marketplace.model';

export interface CheckoutRequest {
  idempotencyKey: string;
  serviceId: number;
  slotStart: string;
  customerName: string;
  customerEmail: string;
}

interface BookingState {
  outcome: BookingOutcome | null;
  submitting: boolean;
  error: string | null;
  /**
   * Generated once per checkout and reused on every retry.
   *
   * The backend enforces idempotency on this key, so a double-clicked button or
   * a retried request returns the first outcome instead of reserving and
   * charging twice. It must therefore survive a failed attempt, and only be
   * regenerated when the customer genuinely starts over.
   */
  idempotencyKey: string;
}

const newKey = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `key-${Date.now()}-${Math.random().toString(16).slice(2)}`;

const initialState: BookingState = {
  outcome: null,
  submitting: false,
  error: null,
  idempotencyKey: newKey(),
};

export const book = createAsyncThunk<
  BookingOutcome,
  Omit<CheckoutRequest, 'idempotencyKey'>,
  { state: { booking: BookingState }; rejectValue: ApiError }
>('booking/book', async (request, { getState, rejectWithValue }) => {
  try {
    const response = await axios.post<BookingOutcome>('/bookings', {
      ...request,
      idempotencyKey: getState().booking.idempotencyKey,
    });
    return response.data;
  } catch (error) {
    return rejectWithValue(error as ApiError);
  }
});

const bookingSlice = createSlice({
  name: 'booking',
  initialState,
  reducers: {
    /** Starts a genuinely new checkout: new key, nothing carried over. */
    checkoutReset(state) {
      state.outcome = null;
      state.error = null;
      state.submitting = false;
      state.idempotencyKey = newKey();
    },
  },
  extraReducers: builder => {
    builder
      .addCase(book.pending, state => {
        state.submitting = true;
        state.error = null;
      })
      .addCase(book.fulfilled, (state, action) => {
        state.submitting = false;
        state.outcome = action.payload;
      })
      .addCase(book.rejected, (state, action) => {
        state.submitting = false;
        state.error = action.payload?.message ?? 'Bokningen misslyckades.';
      });
  },
});

export const { checkoutReset } = bookingSlice.actions;
export default bookingSlice.reducer;
