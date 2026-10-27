/**
 * Chart colours and shared Recharts settings.
 *
 * WHY THESE HEXES, AND WHY YOU SHOULD NOT SWAP THEM BY EYE
 * -------------------------------------------------------
 * Every categorical set below was run through a contrast/colour-vision
 * validator, checking all pairs (not just adjacent ones — a donut lets a reader
 * compare any slice with any other).
 *
 * The first attempt used green / amber / crimson, which looks obvious and is
 * wrong: crimson and green measure ΔE 1.4 under deuteranopia. To a red-green
 * colourblind reader "Approved" and "Rejected" are the same colour. Moving the
 * critical step to pink-600 lifts that pair to ΔE 8.4 while still reading as
 * negative.
 *
 * Amber sits at 2.86:1 against the page, just under the 3:1 line. That is
 * allowed only because every chart using it also prints a visible label and
 * value — the colour never carries the meaning alone. If you remove a legend,
 * re-check the amber.
 *
 * One vocabulary across the portal: PENDING is amber wherever it appears, so a
 * pending application and a pending pet do not have to be learned separately.
 */
export const STATUS = {
  good: "#15803D", // Approved · Available
  warning: "#CA8A04", // Pending          (labels required — see above)
  critical: "#DB2777", // Rejected · Declined
  neutral: "#A21FD1", // Adopted · brand  (also the single-series hue)
  /**
   * Out of play: an application the applicant took back, a pet off the
   * catalogue. One hue for both, because the vocabulary is shared -- the same
   * reason PENDING is amber everywhere.
   *
   * IT IS NOT GREY, AND THAT WAS THE FIRST ATTEMPT. #8A7F93 (the design's
   * muted ink) says "inactive" perfectly to someone with full colour vision
   * and is 4.6 ΔE from Rejected pink under deuteranopia -- two slices of the
   * same donut, indistinguishable, and both meaning "did not adopt". Cyan
   * clears every pair in both palettes under all three CVD simulations.
   */
  inactive: "#0891B2", // Withdrawn · Unavailable
};

/** Single-series bars and lines. One series needs no legend: the title names it. */
export const SERIES = STATUS.neutral;

/**
 * All FOUR statuses the enum has, not the three that make a tidy donut.
 *
 * WITHDRAWN was missing, so the chart's centre total counted three quarters of
 * the applications and called it "Total". A slice worth 1 in 6 of the real
 * figure is not a rounding difference.
 */
export const APPLICATION_STATUS = [
  { key: "Approved", color: STATUS.good },
  { key: "Pending", color: STATUS.warning },
  { key: "Rejected", color: STATUS.critical },
  { key: "Withdrawn", color: STATUS.inactive },
];

/**
 * The four statuses a SHELTER pet can hold. BOARDING and OWNED are absent on
 * purpose: those are household animals (shelter_id IS NULL, per V9), and the
 * chart counts what the network has taken in.
 */
export const PET_STATUS = [
  { key: "Available", color: STATUS.good },
  { key: "Pending", color: STATUS.warning },
  { key: "Adopted", color: STATUS.neutral },
  { key: "Unavailable", color: STATUS.inactive },
];

/** Recessive grid and axes: the data is the ink, the frame is not. */
export const AXIS = {
  tick: { fill: "#8A7F93", fontSize: 12 },
  line: false,
  axisLine: false,
  tickLine: false,
};
export const GRID = { stroke: "#EBDCF3", strokeDasharray: "0", vertical: false };

export const TOOLTIP = {
  cursor: { fill: "rgba(178,11,244,.06)" },
  contentStyle: {
    borderRadius: 14,
    border: "1px solid #E4D2EE",
    boxShadow: "0 12px 28px -14px rgba(178,11,244,.35)",
    fontSize: 13,
    padding: "10px 12px",
  },
  labelStyle: { color: "#1A1420", fontWeight: 600, marginBottom: 4 },
};
