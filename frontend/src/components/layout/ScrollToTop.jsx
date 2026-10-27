import { useLayoutEffect } from "react";
import { useLocation, useNavigationType } from "react-router-dom";

/**
 * Resets the window scroll on navigation.
 *
 * A multi-page site gets this free: every click loads a new document at the
 * top. A single-page app does not -- React Router swaps the component tree and
 * the browser keeps the scroll offset exactly where it was, so clicking
 * "Services" from halfway down "Features" drops you halfway down Services.
 *
 * Three details that are easy to get wrong:
 *
 * 1. useLayoutEffect, not useEffect. useEffect runs AFTER the browser paints,
 *    so the new page is visible at the old offset for one frame and the reset
 *    reads as a flicker. useLayoutEffect runs before paint.
 *
 * 2. POP is excluded. A back/forward button should return you to where you
 *    were, not to the top -- that is what the browser's own scroll restoration
 *    does, and forcing 0 here would fight it. Only PUSH and REPLACE reset.
 *
 * 3. A hash wins. Navigating to /features#matching means "put that element on
 *    screen", so scrolling to the top would be the wrong answer.
 */
export default function ScrollToTop() {
  const { pathname, hash } = useLocation();
  const navigationType = useNavigationType();

  useLayoutEffect(() => {
    if (navigationType === "POP") return;

    if (hash) {
      const el = document.querySelector(hash);
      if (el) {
        el.scrollIntoView({ behavior: "instant", block: "start" });
        return;
      }
    }

    // "instant" rather than "smooth": a page change is not a scroll, and
    // animating one makes navigation feel slow on a long page.
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
  }, [pathname, hash, navigationType]);

  return null;
}
