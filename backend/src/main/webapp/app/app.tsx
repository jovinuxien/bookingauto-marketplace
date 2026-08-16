import React from 'react';
import { BrowserRouter } from 'react-router-dom';

import Header from 'app/shared/layout/header';
import Footer from 'app/shared/layout/footer';
import AppRoutes from 'app/routes';

const App = () => (
  <BrowserRouter>
    <Header />
    <main className="container py-4">
      <AppRoutes />
    </main>
    <Footer />
  </BrowserRouter>
);

export default App;
