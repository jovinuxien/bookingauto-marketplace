import React from 'react';
import { Route, Routes } from 'react-router-dom';

import Home from 'app/modules/home/home';
import SearchResults from 'app/modules/search/search-results';
import ProviderPage from 'app/modules/provider/provider-page';
import Checkout from 'app/modules/booking/checkout';
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
    <Route path="*" element={<NotFound />} />
  </Routes>
);

export default AppRoutes;
