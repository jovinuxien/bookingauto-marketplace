import React from 'react';

interface Props {
  children: React.ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Stops a rendering bug from becoming a blank page.
 *
 * A white screen is the worst failure a consumer site has: it says nothing, and
 * the customer's only option is to leave. This says what happened and offers a
 * way onwards.
 */
export default class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error) {
    // eslint-disable-next-line no-console
    console.error('unhandled error in render', error);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="container py-5">
          <h1 className="h4">Något gick fel</h1>
          <p className="text-muted">Ladda om sidan, eller gå tillbaka till söket.</p>
          <a className="btn btn-primary" href="/">
            Till söket
          </a>
        </div>
      );
    }
    return this.props.children;
  }
}
