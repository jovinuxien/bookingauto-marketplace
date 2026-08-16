import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import { DEFAULT_RADIUS_METRES } from 'app/config/constants';
import type { ApiError } from 'app/config/axios-interceptor';
import type { SearchHit } from 'app/shared/model/marketplace.model';
import { todayInZone } from 'app/shared/util/format';

export interface SearchCriteria {
  lat: number;
  lon: number;
  radius: number;
  day: string;
  when: 'ANY' | 'MORNING' | 'AFTERNOON' | 'EVENING';
  category?: string;
}

interface SearchState {
  criteria: SearchCriteria;
  hits: SearchHit[];
  loading: boolean;
  error: string | null;
  /** False until a search has actually run, so "no results" is not shown to someone who has not searched. */
  searched: boolean;
}

const initialState: SearchState = {
  criteria: {
    lat: 0,
    lon: 0,
    radius: DEFAULT_RADIUS_METRES,
    day: todayInZone(),
    when: 'ANY',
  },
  hits: [],
  loading: false,
  error: null,
  searched: false,
};

export const runSearch = createAsyncThunk<SearchHit[], SearchCriteria, { rejectValue: ApiError }>(
  'search/run',
  async (criteria, { rejectWithValue }) => {
    try {
      const params: Record<string, string | number> = {
        lat: criteria.lat,
        lon: criteria.lon,
        radius: criteria.radius,
        day: criteria.day,
        when: criteria.when,
        limit: 20,
      };
      if (criteria.category) {
        params.category = criteria.category;
      }
      const response = await axios.get<SearchHit[]>('/search', { params });
      return response.data;
    } catch (error) {
      return rejectWithValue(error as ApiError);
    }
  }
);

const searchSlice = createSlice({
  name: 'search',
  initialState,
  reducers: {
    criteriaChanged(state, action: { payload: Partial<SearchCriteria> }) {
      state.criteria = { ...state.criteria, ...action.payload };
    },
  },
  extraReducers: builder => {
    builder
      .addCase(runSearch.pending, state => {
        state.loading = true;
        state.error = null;
      })
      .addCase(runSearch.fulfilled, (state, action) => {
        state.loading = false;
        state.searched = true;
        state.hits = action.payload;
      })
      .addCase(runSearch.rejected, (state, action) => {
        state.loading = false;
        state.searched = true;
        // Results are cleared rather than left stale. Showing the previous
        // search under an error message invites someone to book from it.
        state.hits = [];
        state.error = action.payload?.message ?? 'Sökningen misslyckades.';
      });
  },
});

export const { criteriaChanged } = searchSlice.actions;
export default searchSlice.reducer;
