import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { DaySlots, ProviderDetail } from 'app/shared/model/marketplace.model';

interface ProviderState {
  provider: ProviderDetail | null;
  slots: DaySlots | null;
  loadingProvider: boolean;
  loadingSlots: boolean;
  error: string | null;
}

const initialState: ProviderState = {
  provider: null,
  slots: null,
  loadingProvider: false,
  loadingSlots: false,
  error: null,
};

export const loadProvider = createAsyncThunk<ProviderDetail, string, { rejectValue: ApiError }>(
  'provider/load',
  async (slug, { rejectWithValue }) => {
    try {
      const response = await axios.get<ProviderDetail>(`/providers/${slug}`);
      return response.data;
    } catch (error) {
      return rejectWithValue(error as ApiError);
    }
  }
);

/**
 * The real times, asked of Cal.
 *
 * Separate from loading the provider because it is the expensive call and the
 * one that must not be cached: search may be stale by design, but the times a
 * customer clicks have to be true when they are shown.
 */
export const loadSlots = createAsyncThunk<
  DaySlots,
  { serviceId: number; day: string },
  { rejectValue: ApiError }
>('provider/slots', async ({ serviceId, day }, { rejectWithValue }) => {
  try {
    const response = await axios.get<DaySlots>(`/services/${serviceId}/slots`, { params: { day } });
    return response.data;
  } catch (error) {
    return rejectWithValue(error as ApiError);
  }
});

const providerSlice = createSlice({
  name: 'provider',
  initialState,
  reducers: {
    slotsCleared(state) {
      state.slots = null;
    },
  },
  extraReducers: builder => {
    builder
      .addCase(loadProvider.pending, state => {
        state.loadingProvider = true;
        state.error = null;
      })
      .addCase(loadProvider.fulfilled, (state, action) => {
        state.loadingProvider = false;
        state.provider = action.payload;
      })
      .addCase(loadProvider.rejected, (state, action) => {
        state.loadingProvider = false;
        state.error = action.payload?.message ?? 'Kunde inte hämta salongen.';
      })
      .addCase(loadSlots.pending, state => {
        state.loadingSlots = true;
        // Cleared while loading so a stale list cannot be clicked. The whole
        // point of this call is that these times are current.
        state.slots = null;
        state.error = null;
      })
      .addCase(loadSlots.fulfilled, (state, action) => {
        state.loadingSlots = false;
        state.slots = action.payload;
      })
      .addCase(loadSlots.rejected, (state, action) => {
        state.loadingSlots = false;
        state.error = action.payload?.message ?? 'Kunde inte hämta tider.';
      });
  },
});

export const { slotsCleared } = providerSlice.actions;
export default providerSlice.reducer;
