import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { FieldProblems, Registration, SignupReady } from 'app/shared/model/signup.model';

/**
 * Registration, which is two unrelated screens sharing one slice.
 *
 * The form and the verification landing page never run at the same time — one
 * happens before an email and the other after it, in a different browser as
 * often as not — so they keep separate status fields rather than a shared
 * `loading` that would let one screen's failure show up on the other.
 */
interface SignupState {
  submitting: boolean;
  /** True once the form has been accepted. The page then says "check your email". */
  submitted: boolean;
  problems: FieldProblems;
  /** Set when the whole request failed rather than a field being wrong. */
  error: string | null;

  verifying: boolean;
  ready: SignupReady | null;
  /** Why the link did not work, already worded for a person. */
  verifyError: string | null;
  /** Whether clicking the same link again is worth doing. */
  retryable: boolean;
}

const initialState: SignupState = {
  submitting: false,
  submitted: false,
  problems: {},
  error: null,
  verifying: false,
  ready: null,
  verifyError: null,
  retryable: false,
};

interface RegisterFailure {
  problems?: FieldProblems;
  message?: string;
}

export const register = createAsyncThunk<
  void,
  Registration,
  { rejectValue: RegisterFailure }
>('signup/register', async (registration, { rejectWithValue }) => {
  try {
    await axios.post('/signup', registration);
  } catch (error) {
    const failure = error as ApiError;

    if (failure.status === 400 && failure.data) {
      return rejectWithValue({ problems: failure.data as FieldProblems });
    }
    if (failure.status === 429) {
      return rejectWithValue({
        message: 'För många försök just nu. Vänta en stund och prova igen.',
      });
    }
    return rejectWithValue({ message: failure.message });
  }
});

interface VerifyFailure {
  message: string;
  retryable: boolean;
}

export const verify = createAsyncThunk<
  SignupReady,
  string,
  { rejectValue: VerifyFailure }
>('signup/verify', async (token, { rejectWithValue }) => {
  try {
    const response = await axios.post<SignupReady>('/signup/verify', { token });
    return response.data;
  } catch (error) {
    const failure = error as ApiError;
    const body = (failure.data ?? {}) as { state?: string; message?: string; retryable?: boolean };

    if (failure.status === 410) {
      return rejectWithValue({ message: unusableMessage(body.state), retryable: false });
    }
    if (failure.status === 502) {
      return rejectWithValue({
        message: body.message ?? 'Vi kunde inte slutföra registreringen.',
        retryable: body.retryable ?? false,
      });
    }
    if (failure.status === 429) {
      return rejectWithValue({
        message: 'För många försök just nu. Vänta en stund och prova igen.',
        retryable: true,
      });
    }
    return rejectWithValue({ message: failure.message, retryable: true });
  }
});

/**
 * A link that did not work fails in several ways, and they need different
 * next steps. "Already used" means go and log in; "expired" means register
 * again. One "invalid link" for all of them leaves a person with nowhere to go.
 */
const unusableMessage = (state?: string): string => {
  switch (state) {
    case 'completed':
      return 'Den här länken är redan använd — din salong finns. Logga in för att fortsätta.';
    case 'verifying':
      return 'Registreringen håller på att slutföras. Ladda om sidan om en liten stund.';
    case 'superseded':
      return 'Du har registrerat dig igen sedan det här mejlet skickades. Använd länken i det senaste mejlet.';
    case 'expired':
      return 'Länken har gått ut. Registrera dig igen så skickar vi en ny.';
    default:
      return 'Länken går inte att använda. Registrera dig igen så skickar vi en ny.';
  }
};

const signupSlice = createSlice({
  name: 'signup',
  initialState,
  reducers: {
    /** Clears the form's result so the page can be filled in again. */
    formReset(state) {
      state.submitted = false;
      state.problems = {};
      state.error = null;
    },
  },
  extraReducers: builder => {
    builder
      .addCase(register.pending, state => {
        state.submitting = true;
        state.problems = {};
        state.error = null;
      })
      .addCase(register.fulfilled, state => {
        state.submitting = false;
        state.submitted = true;
      })
      .addCase(register.rejected, (state, action) => {
        state.submitting = false;
        state.problems = action.payload?.problems ?? {};
        state.error = action.payload?.message ?? null;
      })
      .addCase(verify.pending, state => {
        state.verifying = true;
        state.verifyError = null;
      })
      .addCase(verify.fulfilled, (state, action) => {
        state.verifying = false;
        state.ready = action.payload;
      })
      .addCase(verify.rejected, (state, action) => {
        state.verifying = false;
        state.verifyError = action.payload?.message ?? 'Något gick fel.';
        state.retryable = action.payload?.retryable ?? false;
      });
  },
});

export const { formReset } = signupSlice.actions;
export default signupSlice.reducer;
