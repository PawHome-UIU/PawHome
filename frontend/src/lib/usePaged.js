import { useState } from "react";

/**
 * Paging for a list the page already has in hand.
 *
 * ITS OWN FILE, NOT ALONGSIDE <Pagination/>. A module that exports both a
 * component and a hook loses React fast refresh -- editing it full-reloads the
 * app instead of swapping the component -- which eslint's
 * react-refresh/only-export-components says out loud. Hooks live in lib/ here,
 * next to apiError and passwordPolicy.
 *
 * NOT A DATA-FETCHING HOOK. Everything here pages rows already fetched. That
 * is the honest shape for this app right now -- every list endpoint returns
 * its whole result -- and it means the count shown is the real count rather
 * than a guess. The day an endpoint takes a page parameter, THIS is the file
 * that changes and no caller's markup does.
 */

/**
 * Slice a list into pages and hand back what to render.
 *
 * THE CURRENT PAGE IS DERIVED AND CLAMPED, NEVER CORRECTED BY AN EFFECT. Lists
 * shrink under a pager constantly -- a filter narrows, a row is deleted, a
 * reload returns fewer -- and "fix the page number in a useEffect" means an
 * extra render and one frame of an empty page that does have rows. Clamping
 * on the way out is a pure function of what is in hand.
 *
 * pageSize 0 (or a list shorter than one page) means no pager at all. A pager
 * under four rows is furniture.
 */
export function usePaged(rows, pageSize, resetKey) {
  const [page, setPage] = useState(1);

  // BACK TO PAGE ONE WHEN THE LIST BECOMES A DIFFERENT LIST. Pass the tab, the
  // filter, the search term -- whatever means "this is not the same rows any
  // more". Without it, switching from page 3 of History to the Requests tab
  // shows page 3 of Requests, which is nobody's intent; the clamp keeps it
  // legal but it is still the wrong page.
  //
  // ADJUSTED DURING RENDER, NOT IN AN EFFECT. This is React's documented
  // pattern for deriving state from changed input: the component re-renders
  // immediately with the corrected value and nothing is committed to the DOM
  // in between, whereas an effect paints the wrong page first and fixes it
  // one frame later.
  const [seenKey, setSeenKey] = useState(resetKey);
  if (seenKey !== resetKey) {
    setSeenKey(resetKey);
    setPage(1);
  }

  const list = rows ?? [];
  const paged = pageSize > 0 && list.length > pageSize;
  const pageCount = paged ? Math.ceil(list.length / pageSize) : 1;
  const current = Math.min(Math.max(page, 1), pageCount);
  const start = paged ? (current - 1) * pageSize : 0;

  return {
    /** The rows to render. The whole list when there is nothing to page. */
    shown: paged ? list.slice(start, start + pageSize) : list,
    /** Spread straight onto <Pagination {...pager.props} />. */
    props: { page: current, pageCount, pageSize, total: list.length, start, onPage: setPage },
    /** False when the pager should not be drawn at all. */
    paged,
    start,
  };
}
