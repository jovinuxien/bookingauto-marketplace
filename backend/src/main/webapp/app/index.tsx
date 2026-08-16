import React from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';

import 'bootstrap/dist/css/bootstrap.min.css';

import App from 'app/app';
import store from 'app/config/store';
import setupAxiosInterceptors from 'app/config/axios-interceptor';
import { sessionCleared } from 'app/shared/reducers/auth.reducer';
import ErrorBoundary from 'app/shared/error/error-boundary';

// Wired before the first render so no request can escape the error handling.
// A 401 anywhere means the server has stopped accepting the session, and the
// store has to agree with that immediately -- otherwise the console keeps
// rendering a logged-in shell over requests that are all failing.
setupAxiosInterceptors(() => {
  store.dispatch(sessionCleared());
});

const container = document.getElementById('root');
if (!container) {
  throw new Error('no #root element');
}

createRoot(container).render(
  <React.StrictMode>
    <ErrorBoundary>
      <Provider store={store}>
        <App />
      </Provider>
    </ErrorBoundary>
  </React.StrictMode>
);
