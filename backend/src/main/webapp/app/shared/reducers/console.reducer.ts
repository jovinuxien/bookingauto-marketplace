import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { AttentionItem, ConsoleBooking, ConsoleSummary } from 'app/shared/model/console.model';

interface ConsoleState {
  /** Per booking id, while its cancellation is in flight. */
  cancellingId: number | null;
  cancelNotice: string | null;
  summary: ConsoleSummary | null;
  bookings: ConsoleBooking[];
  attention: AttentionItem[];
  loading: boolean;
  error: string | null;
}

const initialState: ConsoleState = {
  cancellingId: null,
  cancelNotice: null,
  summary: null,
  bookings: [],
  attention: [],
  loading: false,
  error: null,
};

/**
 * Loads the whole console at once.
 *
 * One thunk rather than three because the screen is meaningless in pieces: a
 * booking list without the payability banner above it can tell a salon it has
 * earned money it cannot yet be paid.
 */
export const cancelAsProvider = createAsyncThunk<
  { outcome: string; refunded: boolean; id: number },
  number,
  { rejectValue: ApiError }
>('console/cancelBooking', async (id, { rejectWithValue, dispatch }) => {
  try {
    const response = await axios.post<{ outcome: string; refunded: boolean }>(`/console/bookings/${id}/cancel`);
    void dispatch(loadConsole());
    return { ...response.data, id };
  } catch (error) {
    return rejectWithValue(error as ApiError);
  }
});

export const loadConsole = createAsyncThunk<
  { summary: ConsoleSummary; bookings: ConsoleBooking[]; attention: AttentionItem[] },
  void,
  { rejectValue: ApiError }
>('console/load', async (_, { rejectWithValue }) => {
  try {
    const [summary, bookings, attention] = await Promise.all([
      axios.get<ConsoleSummary>('/console/summary'),
      axios.get<ConsoleBooking[]>('/console/bookings'),
      axios.get<AttentionItem[]>('/console/attention'),
    ]);
    return { summary: summary.data, bookings: bookings.data, attention: attention.data };
  } catch (error) {
    return rejectWithValue(error as ApiError);
  }
});

const consoleSlice = createSlice({
  name: 'console',
  initialState,
  extraReducers: builder => {
    builder
      .addCase(loadConsole.pending, state => {
        state.loading = true;
        state.error = null;
      })
      .addCase(loadConsole.fulfilled, (state, action) => {
        state.loading = false;
        state.summary = action.payload.summary;
        state.bookings = action.payload.bookings;
        state.attention = action.payload.attention;
      })
      .addCase(loadConsole.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload?.message ?? 'Kunde inte hämta konsolen.';
      })
      .addCase(cancelAsProvider.pending, (state, action) => {
        state.cancellingId = action.meta.arg;
        state.cancelNotice = null;
      })
      .addCase(cancelAsProvider.fulfilled, (state, action) => {
        state.cancellingId = null;
        state.cancelNotice =
          action.payload.outcome === 'refund_pending'
            ? 'Tiden är avbokad. Återbetalningen behandlas manuellt — kunden är informerad.'
            : 'Tiden är avbokad och kunden får hela beloppet tillbaka.';
      })
      .addCase(cancelAsProvider.rejected, (state, action) => {
        state.cancellingId = null;
        state.cancelNotice = action.payload?.status === 409
          ? 'Tiden har redan börjat och kan inte avbokas här.'
          : 'Avbokningen gick inte igenom. Prova igen om en stund.';
      });
  },
  reducers: {},
});

export default consoleSlice.reducer;
