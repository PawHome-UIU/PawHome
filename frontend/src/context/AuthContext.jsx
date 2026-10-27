import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import api from "../api/client";

/**
 * Who is signed in, for the whole application.
 *
 * THE SERVER IS THE AUTHORITY, NOT localStorage. On boot this calls
 * GET /auth/me with whatever token is stored and believes the answer. The
 * cached user object is only used to avoid a blank screen during that call.
 *
 * That matters because localStorage is trivially editable. Someone can open
 * devtools and write {"role":"ADMIN"} into it -- and they should get an empty
 * admin portal and a wall of 403s, because every request still carries their
 * real token and the backend reloads their real row. The UI is a convenience;
 * it is not the access control, and this file is written so that stays true.
 */
const AuthContext = createContext(null);

const TOKEN_KEY = "token";
const USER_KEY = "user";

function cachedUser() {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    // A half-written or hand-edited value. Treat it as no user rather than
    // crashing the whole app on boot.
    return null;
  }
}

export function AuthProvider({ children }) {
  // NO TOKEN MEANS NO USER, DECIDED AT MOUNT RATHER THAN IN THE EFFECT.
  //
  // This used to read the cached user unconditionally and then setUser(null)
  // from the effect body when there was no token -- a synchronous setState in
  // an effect, which React flags and which renders the signed-out visitor as
  // the previous account for one frame. The question "is there a token" has an
  // answer before the first render, so it is answered there.
  //
  // A cached user with no token is a leftover (storage cleared by the 401
  // interceptor in another tab, or a hand-edited value). It is dropped below
  // rather than trusted.
  const [user, setUser] = useState(
    () => (localStorage.getItem(TOKEN_KEY) ? cachedUser() : null));
  // "checking" until /auth/me has answered. Routes must not decide anything
  // while this is true, or a refresh on /admin bounces to /login for the
  // fraction of a second before the token is confirmed.
  const [checking, setChecking] = useState(!!localStorage.getItem(TOKEN_KEY));

  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      // Not state -- storage. `user` is already null and `checking` already
      // false from the initialisers above, so there is nothing to set.
      localStorage.removeItem(USER_KEY);
      return undefined;
    }

    let cancelled = false;
    api
      .get("/auth/me")
      .then(({ data }) => {
        if (cancelled) return;
        setUser(data);
        localStorage.setItem(USER_KEY, JSON.stringify(data));
      })
      .catch(() => {
        // 401 is already handled by the client interceptor (it clears storage
        // and redirects). Anything else -- backend down, network error -- must
        // not silently sign the user out, so only the local state is cleared.
        if (!cancelled) setUser(null);
      })
      .finally(() => {
        if (!cancelled) setChecking(false);
      });

    return () => { cancelled = true; };
  }, []);

  const login = useCallback((token, nextUser) => {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser));
    setUser(nextUser);
    setChecking(false);
  }, []);

  /**
   * Replace the cached account with one the server just returned.
   *
   * Settings changes the signed-in person's own name, avatar and notification
   * switches, and the sidebar reads all three from here. Without this the
   * header would keep showing the old name until the next full reload, which
   * reads as the save not having worked.
   */
  const applyUser = useCallback((nextUser) => {
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser));
    setUser(nextUser);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ user, checking, login, logout, applyUser, role: user?.role ?? null }),
    [user, checking, login, logout, applyUser]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>.");
  return ctx;
}

/** Where each role lands after signing in. */
export const HOME_FOR = { ADMIN: "/admin", STAFF: "/staff", ADOPTER: "/app" };
