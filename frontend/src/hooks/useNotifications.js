import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import api from "../api/client";
import { WS_BASE } from "../lib/config";

/**
 * The bell's data: what is unread, and the last few.
 *
 * TWO SOURCES, ONE STATE. The REST call is the truth on load and after any
 * action; the socket is a nudge that something changed. They are not
 * alternatives -- a socket alone would miss everything that happened while the
 * tab was closed, and REST alone is the polling this replaces.
 *
 * THE FRAME IS TREATED AS A SIGNAL, NOT AS THE NEW STATE. The server pushes
 * the new notification and the fresh unread count, and this could simply
 * prepend it -- but then two tabs, or a reconnect that replays, would show the
 * same row twice. Re-reading the inbox costs one indexed query and cannot
 * disagree with the database, so the push sets the count immediately (the dot
 * is what people notice) and refetches the list behind it.
 *
 * THE SOCKET IS OPTIONAL BY DESIGN. Every failure path here is silent: no
 * token, a refused CONNECT, a broker that is down, a proxy that will not
 * upgrade. The bell still works from the REST call in all of them -- it just
 * updates when the page does. A notification system that breaks the header
 * when the socket is unavailable would be worse than no socket.
 */
export default function useNotifications(enabled) {
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState([]);
  const clientRef = useRef(null);

  const load = useCallback(async () => {
    if (!enabled) return;
    try {
      const { data } = await api.get("/notifications");
      setUnread(data.unread);
      setItems(data.items);
    } catch {
      // Quiet: a header that renders an error is worse than one that shows
      // nothing new. The next action reloads it.
    }
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;

    (async () => {
      try {
        const { data } = await api.get("/notifications");
        if (cancelled) return;
        setUnread(data.unread);
        setItems(data.items);
      } catch {
        // See above.
      }
    })();

    const token = localStorage.getItem("token");
    if (!token) return () => { cancelled = true; };

    const client = new Client({
      brokerURL: `${WS_BASE}/ws`,
      // Authorization on the STOMP CONNECT frame, not on the HTTP handshake:
      // the browser's WebSocket API cannot set a header on the opening
      // request, and the alternative -- a token in the query string -- lands
      // in every access log and proxy trace between here and the server.
      connectHeaders: { Authorization: `Bearer ${token}` },
      // Long enough not to hammer a backend that is restarting, short enough
      // that a laptop coming out of sleep reconnects before anyone notices.
      reconnectDelay: 5000,
      // The STOMP client logs every frame at info by default, which fills the
      // console with heartbeats and hides everything else.
      debug: () => {},
    });

    client.onConnect = () => {
      // /user/... is Spring's per-principal destination: the broker rewrites
      // it to this session alone, so there is no subscription anyone could
      // guess to read somebody else's notifications.
      client.subscribe("/user/queue/notifications", (frame) => {
        try {
          const payload = JSON.parse(frame.body);
          // The count first -- it is what the eye catches -- then the list
          // from the server, so two tabs cannot drift apart.
          if (typeof payload.unread === "number") setUnread(payload.unread);
        } catch {
          // A frame we cannot parse is not worth breaking the header over.
        }
        load();
      });
    };

    // Deliberately empty handlers rather than the library's default, which
    // logs to the console on every disconnect during a backend restart.
    client.onStompError = () => {};
    client.onWebSocketError = () => {};

    client.activate();
    clientRef.current = client;

    return () => {
      cancelled = true;
      clientRef.current = null;
      // deactivate() is async and returns a promise nobody can await in a
      // cleanup; letting it settle on its own is correct, and the client
      // refuses to reconnect once deactivated.
      client.deactivate();
    };
  }, [enabled, load]);

  const markAllRead = useCallback(async () => {
    try {
      const { data } = await api.post("/notifications/read");
      setUnread(data.unread);
      setItems(data.items);
    } catch { /* leave the badge as it was */ }
  }, []);

  const markRead = useCallback(async (id) => {
    try {
      const { data } = await api.post(`/notifications/${id}/read`);
      setUnread(data.unread);
      setItems(data.items);
    } catch { /* leave the badge as it was */ }
  }, []);

  return { unread, items, reload: load, markAllRead, markRead };
}
