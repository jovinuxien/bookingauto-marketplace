import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import { DEFAULT_RADIUS_METRES } from 'app/config/constants';
import type { ApiError } from 'app/config/axios-interceptor';
import type { AskedAnswer, SearchHit } from 'app/shared/model/marketplace.model';
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
  /**
   * How the last sentence was read, or null if the last search was filters.
   *
   * Kept in the store rather than the URL because it describes one answer
   * rather than one query: it must survive the filter change that the answer
   * itself causes, and must not survive a filter the customer then edits by
   * hand — at that point they are no longer looking at what we understood.
   */
  interpretation: Interpretation | null;
  interpreting: boolean;
}

export interface Interpretation {
  summary: string | null;
  ignored: string[];
  categorySlug: string | null;
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
  interpretation: null,
  interpreting: false,
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

export interface AskCriteria {
  q: string;
  lat: number;
  lon: number;
  radius: number;
}

/**
 * Search by sentence.
 *
 * Separate thunk and separate endpoint, because it is a different thing to buy:
 * `/search` is one indexed query, this puts a model in front of it and is
 * metered. Keeping them apart is what lets the sentence be optional at every
 * level — off in a deployment, refused by a rate limit, or simply not typed —
 * without the ordinary search knowing.
 *
 * The longer timeout is for the model, not the query. It is still a timeout:
 * the caller falls back to a plain search rather than showing an error, which
 * mirrors what the endpoint itself does when the model does not answer.
 */
export const askSearch = createAsyncThunk<AskedAnswer, AskCriteria, { rejectValue: ApiError }>(
  'search/ask',
  async (criteria, { rejectWithValue }) => {
    try {
      const response = await axios.get<AskedAnswer>('/search/ask', {
        params: { q: criteria.q, lat: criteria.lat, lon: criteria.lon, radius: criteria.radius, limit: 20 },
        timeout: 30000,
      });
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
    /**
     * Forgets how the sentence was read.
     *
     * Dispatched when a filter is changed by hand. From that moment the results
     * are not what we understood, and leaving the note up would attribute the
     * customer's own edit to us.
     */
    interpretationDropped(state) {
      state.interpretation = null;
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
      .addCase(askSearch.pending, state => {
        state.interpreting = true;
        state.loading = true;
        state.error = null;
      })
      .addCase(askSearch.fulfilled, (state, action) => {
        state.interpreting = false;
        state.loading = false;
        state.searched = true;
        state.hits = action.payload.hits;
        state.interpretation = {
          summary: action.payload.summary,
          ignored: action.payload.ignored,
          categorySlug: action.payload.applied.categorySlug,
        };
      })
      .addCase(askSearch.rejected, state => {
        // No error shown and no results cleared. The caller runs an ordinary
        // search next, which is the same answer the endpoint gives itself when
        // the model does not reply — a sentence nobody could read should cost
        // the customer their filters, not their results.
        state.interpreting = false;
        state.interpretation = null;
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

export const { criteriaChanged, interpretationDropped } = searchSlice.actions;
export default searchSlice.reducer;
