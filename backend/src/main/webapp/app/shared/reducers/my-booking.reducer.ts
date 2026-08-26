import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { MyBooking } from 'app/shared/model/my-booking.model';

/**
 * The page a customer reaches from their confirmation email.
 *
 * Two calls that both answer with the same booking, which is what lets the page
 * stay one shape: looking it up and cancelling it differ in what they did, not
 * in what they return. A cancel that fails still hands back the booking, so the
 * screen never has to fall back to showing nothing.
 */
interface MyBookingState {
  loading: boolean;
  booking: MyBooking | null;
  /** Why the booking could not be shown at all. */
  error: string | null;
  cancelling: boolean;
  /** Why the cancellation did not happen. The booking is still on screen. */
  cancelError: string | null;
  /** True for the one render after a successful cancellation. */
  justCancelled: boolean;
}

const initialState: MyBookingState = {
  loading: false,
  booking: null,
  error: null,
  cancelling: false,
  cancelError: null,
  justCancelled: false,
};

export const loadBooking = createAsyncThunk<MyBooking, string, { rejectValue: ApiError }>(
  'myBooking/load',
  async (token, { rejectWithValue }) => {
    try {
      const response = await axios.post<MyBooking>('/bookings/lookup', { token });
      return response.data;
    } catch (error) {
      return rejectWithValue(error as ApiError);
    }
  }
);

interface CancelFailure {
  /** Present whenever the server knew which booking it was refusing to cancel. */
  booking?: MyBooking;
  message: string;
}

export const cancelBooking = createAsyncThunk<MyBooking, string, { rejectValue: CancelFailure }>(
  'myBooking/cancel',
  async (token, { rejectWithValue }) => {
    try {
      const response = await axios.post<MyBooking>('/bookings/cancel', { token });
      return response.data;
    } catch (error) {
      const failure = error as ApiError;

      // 409 and 502 both carry the booking, so the page can keep showing it
      // while explaining why it is still there.
      return rejectWithValue({
        booking: failure.data as MyBooking | undefined,
        message: messageFor(failure.status),
      });
    }
  }
);

const messageFor = (status: number): string => {
  switch (status) {
    case 409:
      return 'Tiden har redan börjat, så den går inte att avboka här. Kontakta salongen.';
    case 502:
      // The booking is cancelled on our side and the salon's calendar disagrees.
      // Saying "try again" would be wrong — a second attempt cannot help — so
      // this says what is true: it is being sorted out.
      return 'Avbokningen är registrerad men kalendern svarade inte. Vi rättar till det, och salongen får besked.';
    case 404:
      return 'Länken gäller inte längre.';
    case 429:
      return 'För många försök. Vänta en stund och ladda om sidan.';
    default:
      return 'Något gick fel. Försök igen om en stund.';
  }
};

const myBookingSlice = createSlice({
  name: 'myBooking',
  initialState,
  reducers: {},
  extraReducers: builder => {
    builder
      .addCase(loadBooking.pending, state => {
        state.loading = true;
        state.error = null;
      })
      .addCase(loadBooking.fulfilled, (state, action) => {
        state.loading = false;
        state.booking = action.payload;
      })
      .addCase(loadBooking.rejected, (state, action) => {
        state.loading = false;
        state.error =
          action.payload?.status === 404
            ? 'Vi hittar ingen bokning för den här länken. Kontrollera att du kopierat hela adressen från mejlet.'
            : messageFor(action.payload?.status ?? 0);
      })
      .addCase(cancelBooking.pending, state => {
        state.cancelling = true;
        state.cancelError = null;
      })
      .addCase(cancelBooking.fulfilled, (state, action) => {
        state.cancelling = false;
        state.booking = action.payload;
        state.justCancelled = true;
      })
      .addCase(cancelBooking.rejected, (state, action) => {
        state.cancelling = false;
        state.cancelError = action.payload?.message ?? 'Något gick fel.';

        // The server's version of the booking wins. It knows, for instance,
        // that the appointment has already started, which is exactly why it
        // refused — and leaving the stale copy on screen would keep offering
        // the button that just failed.
        if (action.payload?.booking) {
          state.booking = action.payload.booking;
        }
      });
  },
});

export default myBookingSlice.reducer;
