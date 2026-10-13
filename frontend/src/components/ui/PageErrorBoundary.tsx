import { Component, type ReactNode } from 'react'
import { ErrorState } from './States'

interface State {
  failed: boolean
}

/** Shows an error with a reload button instead of unmounting the whole app when a page fails to load or render. */
export class PageErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  render() {
    if (this.state.failed) {
      return (
        <div className="p-6">
          <ErrorState
            message="This page could not be loaded. Reload to try again."
            onRetry={() => window.location.reload()}
          />
        </div>
      )
    }
    return this.props.children
  }
}
