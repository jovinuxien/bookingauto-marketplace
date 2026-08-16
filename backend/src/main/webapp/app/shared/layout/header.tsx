import React from 'react';
import { Link } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { logout } from 'app/shared/reducers/auth.reducer';

const Header = () => {
  const dispatch = useAppDispatch();
  const session = useAppSelector(state => state.auth.session);

  return (
    <nav className="navbar navbar-expand navbar-dark bg-dark">
      <div className="container">
        <Link className="navbar-brand fw-semibold" to="/">
          Boka
        </Link>
        <div className="d-flex align-items-center gap-3">
          {session ? (
            <>
              <Link className="navbar-text small text-white" to="/konsol">
                {session.displayName ?? session.email}
              </Link>
              <button className="btn btn-sm btn-outline-light" onClick={() => dispatch(logout())}>
                Logga ut
              </button>
            </>
          ) : (
            <Link className="navbar-text small text-white" to="/logga-in">
              För salonger
            </Link>
          )}
        </div>
      </div>
    </nav>
  );
};

export default Header;
