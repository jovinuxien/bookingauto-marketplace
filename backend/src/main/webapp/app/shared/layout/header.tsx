import React from 'react';
import { Link } from 'react-router-dom';

const Header = () => (
  <nav className="navbar navbar-expand navbar-dark bg-dark">
    <div className="container">
      <Link className="navbar-brand fw-semibold" to="/">
        Boka
      </Link>
      <span className="navbar-text small">Hitta lediga tider nära dig</span>
    </div>
  </nav>
);

export default Header;
