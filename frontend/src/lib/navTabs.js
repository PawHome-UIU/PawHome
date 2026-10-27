/**
 * The tab rows that sit inside a portal section.
 *
 * IN lib/ RATHER THAN BESIDE THE COMPONENT, because a file that exports both a
 * component and a constant breaks React Fast Refresh -- editing it forces a
 * full page reload instead of a hot swap. That is the react-refresh rule the
 * linter raises, and it is worth obeying here: these arrays change far less
 * often than the component that renders them.
 *
 * DEFINED ONCE for the same reason any shared list is: writing them out on each
 * of the three pages is how a fourth tab gets added to two of them and
 * forgotten on the third, leaving a page with no way back.
 */

/** Leaving your pet somewhere, and what it cost. Three views of one errand. */
export const ADOPTER_BOARDING_TABS = [
  { to: "/app/boarding", label: "Boarding" },
  { to: "/app/daycare", label: "Daycare" },
  { to: "/app/slips", label: "Bills" },
];

/** The shelter's side of the same three. */
export const STAFF_BOARDING_TABS = [
  { to: "/staff/boarding", label: "Boarding" },
  { to: "/staff/daycare", label: "Daycare" },
  { to: "/staff/slips", label: "Bills" },
];
