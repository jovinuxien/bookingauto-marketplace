import React from 'react';
import { BrowserRouter } from 'react-router-dom';

import Header from 'app/shared/layout/header';
import Footer from 'app/shared/layout/footer';
import AppRoutes from 'app/routes';

const App = () => {
  // The widget lives inside an iframe on someone else's site (ADR 0018):
  // no header, no footer, no container — the host page is the chrome.
  const embedded = window.location.pathname.startsWith('/widget/');

  if (embedded) {
    return (
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    );
  }

  return (
    <BrowserRouter>
      <Header />
      <main className="container py-4">
        <AppRoutes />
      </main>
      <Footer />
    </BrowserRouter>
  );
};

export default App;
