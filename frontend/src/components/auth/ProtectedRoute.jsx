import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth, HOME_FOR } from "../../context/AuthContext";

/**
 * Route guard.
 *
 * THIS IS NOT SECURITY. It is navigation. Everything it does happens in the
 * browser, where the person being guarded controls all of it -- they can edit
 * localStorage, or run the bundle with this component deleted.
 *
 * The actual access control is SecurityConfig and ScopeGuard on the server, and
 * that is what has to be right. What this buys is that an adopter who types
 * /admin gets an honest "not for you" instead of a shell full of empty tables
 * and console errors from a dozen 403s.
 *
 * If removing this file changed what data anyone could reach, the backend would
 * be broken -- so it is worth occasionally checking that it does not.
 *
 * Three outcomes:
 *   not signed in  -> /login, remembering where they were headed
 *   wrong role     -> their own portal (not a 403 page: they are not doing
 *                     anything wrong, they are just in the wrong place)
 *   right role     -> render
 */
export default function ProtectedRoute({ roles }) {
  const { user, checking } = useAuth();
  const location = useLocation();

  // Still asking the server who this is. Rendering either branch now would
  // flash the wrong thing on every refresh.
  if (checking) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-pw-tint">
        <div className="flex flex-col items-center gap-3">
          <span className="h-8 w-8 animate-spin rounded-full border-2 border-pw-stroke border-t-pw-button" />
          <p className="text-sm text-pw-muted">Checking your session…</p>
        </div>
      </div>
    );
  }

  if (!user) {
    // `state.from` lets the login page send them back where they meant to go.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (roles && !roles.includes(user.role)) {
    return <Navigate to={HOME_FOR[user.role] ?? "/"} replace />;
  }

  return <Outlet />;
}
