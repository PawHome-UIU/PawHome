import axios from "axios";
import { API_BASE } from "../lib/config";

// Talks to the Spring Boot backend. All routes live under /api/v1.
const api = axios.create({ baseURL: API_BASE });

// Attach the JWT on every request.
//
// TODO: this reads localStorage, which means an XSS becomes token theft. The
// trade-off buys structural CSRF immunity (no cookies). If you move the token
// into an httpOnly cookie later, re-enable CSRF in SecurityConfig.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Auto-logout on an expired/missing token (401).
//
// 403 is deliberately NOT handled here: that is a role failure, and the calling
// component should show it inline rather than being bounced to the login page.
api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem("token");
      localStorage.removeItem("user");
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }
    return Promise.reject(err);
  },
);

export default api;
