import React from 'react';
import { Route, Routes } from 'react-router-dom';

import Home from 'app/modules/home/home';
import SearchResults from 'app/modules/search/search-results';
import ProviderPage from 'app/modules/provider/provider-page';
import Checkout from 'app/modules/booking/checkout';
import Login from 'app/modules/login/login';
import Register from 'app/modules/signup/register';
import Verify from 'app/modules/signup/verify';
import Console from 'app/modules/console/console';
import PrivateRoute from 'app/shared/auth/private-route';
import NotFound from 'app/shared/error/not-found';

/**
 * The consumer journey, and nothing else.
 *
 * Paths are Swedish because they are the product's public URLs: a marketplace
 * is found through them, and /salong/salong-sodermalm is what a customer would
 * expect to see and share.
 */
const AppRoutes = () => (
  <Routes>
    <Route path="/" element={<Home />} />
    <Route path="/sok" element={<SearchResults />} />
    <Route path="/salong/:slug" element={<ProviderPage />} />
    <Route path="/boka/:serviceId" element={<Checkout />} />

    {/* The business side. Guarded here for rendering; enforced on the server. */}
    <Route path="/registrera" element={<Register />} />
    {/* Where the verification email lands. Public by necessity: the token in
        the query string is the only credential the visitor has. */}
    <Route path="/verifiera" element={<Verify />} />
    <Route path="/logga-in" element={<Login />} />
    <Route path="/konsol" element={<PrivateRoute><Console /></PrivateRoute>} />
    <Route path="*" element={<NotFound />} />
  </Routes>
);

export default AppRoutes;
