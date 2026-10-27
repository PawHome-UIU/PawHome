// Pull a readable sentence out of an axios error.
//
// The backend's GlobalExceptionHandler always returns { "error": "<sentence>" },
// so that is the first thing to look for. Everything else is a fallback.
export function apiError(err, fallback = "Something went wrong.") {
  const body = err?.response?.data;
  if (body?.error) return body.error;
  if (body?.fields) {
    const first = Object.entries(body.fields)[0];
    if (first) return `${first[0]}: ${first[1]}`;
  }
  if (err?.message === "Network Error") {
    return "Could not reach the server. Is the backend running on port 8081?";
  }
  return fallback;
}

export default apiError;
