import React from 'react';
import { Link } from 'react-router-dom';

const NotFound = () => (
  <div className="container py-5">
    <h1 className="h4">Sidan finns inte</h1>
    <Link to="/">Till söket</Link>
  </div>
);

export default NotFound;
