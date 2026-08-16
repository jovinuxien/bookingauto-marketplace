import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { Session } from 'app/shared/model/console.model';

interface AuthState {
  session: Session | null;
  loading: boolean;
  error: string | null;
  /**
   * False until the "who am I" call has answered.
   *
   * Without it the console flickers to the login screen on every reload, and a
   * refresh mid-work looks like being logged out.
   */
  resolved: boolean;
}

const initialState: AuthState = { session: null, loading: false, error: null, resolved: false };

export const login = createAsyncThunk<
  Session,
  { email: string; password: string },
  { rejectValue: ApiError }
>('auth/login', async (credentials, { rejectWithValue }) => {
  try {
    const response = await axios.post<Session>('/auth/login', credentials);
    return response.data;
  } catch (error) {
    return rejectWithValue(error as ApiError);
  }
});

/** Restores a session from the cookie the browser already holds. */
export const loadSession = createAsyncThunk<Session | null>('auth/me', async () => {
  try {
    const response = await axios.get<Session>('/auth/me');
    return response.data;
  } catch {
    // 401 here is the normal case: nobody is logged in. Not an error state.
    return null;
  }
});

export const logout = createAsyncThunk('auth/logout', async () => {
  await axios.post('/auth/logout');
});

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    /** Called by the axios interceptor when the server stops accepting the session. */
    sessionCleared(state) {
      state.session = null;
      state.resolved = true;
    },
  },
  extraReducers: builder => {
    builder
      .addCase(login.pending, state => {
        state.loading = true;
        state.error = null;
      })
      .addCase(login.fulfilled, (state, action) => {
        state.loading = false;
        state.resolved = true;
        state.session = action.payload;
      })
      .addCase(login.rejected, state => {
        state.loading = false;
        state.resolved = true;
        // One message regardless of which half was wrong, matching the server.
        state.error = 'Fel e-post eller lösenord.';
      })
      .addCase(loadSession.fulfilled, (state, action) => {
        state.resolved = true;
        state.session = action.payload;
      })
      .addCase(loadSession.rejected, state => {
        state.resolved = true;
        state.session = null;
      })
      .addCase(logout.fulfilled, state => {
        state.session = null;
      });
  },
});

export const { sessionCleared } = authSlice.actions;
export default authSlice.reducer;
