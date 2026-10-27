import { FiChevronLeft, FiChevronRight } from "react-icons/fi";

/**
 * One pager, for every list in the product.
 *
 * THIS LOGIC LIVED INSIDE DataTable AND NOWHERE ELSE, which is why half the
 * app had no paging: the admin tables inherited it by using that component,
 * and every hand-rolled table -- the staff bills, the daycare subscriptions,
 * the adopter's own slips -- got nothing, because writing a pager from scratch
 * to add one row to a page is not a thing anybody does. Moving it out here
 * makes paging a two-line change instead of a hundred-line one.
 *
 * DataTable now consumes this rather than duplicating it, so the pager under a
 * table and the pager under a list are the same pager, not two that look alike
 * until one of them is fixed.
 *
 * THE SLICING LIVES IN lib/usePaged, not here: a module exporting both a
 * component and a hook loses fast refresh. This file is the bar you can see;
 * that one is the arithmetic behind it.
 */

/**
 * The bar itself.
 *
 * THE COUNT IS SPELLED OUT, not just "Page 2 of 4" -- that alone never tells
 * anyone how much they are looking at, which is the first thing somebody
 * scanning a list of bills wants to know.
 */
export default function Pagination({
  page, pageCount, pageSize, total, start, onPage,
  className = "",
  noun = "",
  /**
   * Whether to draw the hairline above the bar.
   *
   * A PROP RATHER THAN A className OVERRIDE. Inside a Card the pager is the
   * last row of a panel and needs the rule to separate it from the rows above;
   * under a bare grid there is nothing above it to separate from. Trying to
   * turn a base `border-t` off with `border-t-0` in className depends on which
   * class Tailwind emits last, which is not something a caller should have to
   * know or a refactor should be able to break.
   */
  bordered = true,
}) {
  if (pageCount <= 1) return null;

  return (
    <div className={`flex flex-wrap items-center justify-between gap-3 px-5 py-3.5 ${
      bordered ? "border-t border-pw-stroke/40" : ""} ${className}`}>
      <p className="text-sm text-pw-muted">
        Showing <span className="font-medium tabular-nums text-pw-ink">
          {start + 1}–{Math.min(start + pageSize, total)}
        </span>{" "}
        of <span className="font-medium tabular-nums text-pw-ink">{total}</span>
        {noun && ` ${noun}`}
      </p>

      <nav className="flex items-center gap-1" aria-label="Pagination">
        <PageButton onClick={() => onPage(page - 1)} disabled={page === 1} label="Previous page">
          <FiChevronLeft className="h-4 w-4" />
        </PageButton>

        {pageNumbers(page, pageCount).map((n, i) =>
          n === "gap" ? (
            // A real gap in the sequence, not a button. Hidden from screen
            // readers, because it says nothing one needs.
            <span key={`gap${i}`} aria-hidden="true" className="px-1 text-sm text-pw-muted">…</span>
          ) : (
            <PageButton key={n} onClick={() => onPage(n)} active={n === page} label={`Page ${n}`}>
              {n}
            </PageButton>
          ))}

        <PageButton onClick={() => onPage(page + 1)} disabled={page === pageCount} label="Next page">
          <FiChevronRight className="h-4 w-4" />
        </PageButton>
      </nav>
    </div>
  );
}

function PageButton({ children, onClick, disabled = false, active = false, label }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      // aria-current, not just a colour. The active page has to be announced,
      // or a screen-reader user has a row of numbers and no idea where in the
      // list they are.
      aria-current={active ? "page" : undefined}
      className={[
        "flex h-8 min-w-[2rem] items-center justify-center rounded-pill px-2 text-sm",
        "transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-pw-button/40",
        active
          ? "bg-pw-button font-semibold text-white"
          : "text-pw-body hover:bg-pw-input disabled:text-pw-muted/50 disabled:hover:bg-transparent",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

/**
 * First, last, and a window around the current page.
 *
 * Rendering every page number is fine at four pages and unusable at forty, and
 * a shelter's list of bills is the kind that only ever grows.
 */
function pageNumbers(current, count) {
  if (count <= 7) return Array.from({ length: count }, (_, i) => i + 1);

  const out = [1];
  const from = Math.max(2, current - 1);
  const to = Math.min(count - 1, current + 1);

  if (from > 2) out.push("gap");
  for (let n = from; n <= to; n++) out.push(n);
  if (to < count - 1) out.push("gap");

  out.push(count);
  return out;
}
