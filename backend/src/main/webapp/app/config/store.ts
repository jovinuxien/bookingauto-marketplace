import { configureStore } from '@reduxjs/toolkit';
import { useDispatch, useSelector } from 'react-redux';
import type { TypedUseSelectorHook } from 'react-redux';

import search from 'app/shared/reducers/search.reducer';
import provider from 'app/shared/reducers/provider.reducer';
import booking from 'app/shared/reducers/booking.reducer';
import auth from 'app/shared/reducers/auth.reducer';
import consoleReducer from 'app/shared/reducers/console.reducer';

const store = configureStore({
  reducer: { search, provider, booking, auth, console: consoleReducer },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;

/** Typed hooks, so a component cannot quietly select a field that no longer exists. */
export const useAppDispatch: () => AppDispatch = useDispatch;
export const useAppSelector: TypedUseSelectorHook<RootState> = useSelector;

export default store;
