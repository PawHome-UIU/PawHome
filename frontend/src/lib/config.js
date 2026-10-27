// Single source of truth for where the backend lives.
//
// Hardcoding the host in several files is how an app ends up only working on
// localhost -- demoing from a second machine then fails silently with no
// obvious cause. Everything derives from this one value.
//
// Vite only exposes VITE_-prefixed variables and inlines them at BUILD time,
// so this must be read at module scope, not inside a function.

export const API_BASE =
  import.meta.env.VITE_API_URL || "http://localhost:8081/api/v1";

// WebSocket origin is DERIVED, so there is only one thing to configure:
// strip the /api/v1 suffix and swap http -> ws (https -> wss).
export const WS_BASE = API_BASE.replace(/\/api\/v1\/?$/, "").replace(
  /^http/,
  "ws",
);

export default { API_BASE, WS_BASE };
