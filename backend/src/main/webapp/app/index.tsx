import React from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';

import 'bootstrap/dist/css/bootstrap.min.css';

import App from 'app/app';
import store from 'app/config/store';
import setupAxiosInterceptors from 'app/config/axios-interceptor';
import ErrorBoundary from 'app/shared/error/error-boundary';

// Wired before the first render so no request can escape the error handling.
setupAxiosInterceptors(() => {
  // Nothing to clear yet: the consumer journey is anonymous until checkout.
  // Left explicit rather than omitted, because this is where session handling
  // will hang when accounts exist.
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
