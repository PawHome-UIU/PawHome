/**
 * How a pet's age is written, in one place.
 *
 * The database stores a number and a unit -- age_value + age_unit -- because
 * V1 says shelters rarely know a rescue animal's birth date and the Add Pet
 * form does not ask for one. Every screen therefore has to turn that pair back
 * into words, and three copies of the same ternary is how one of them ends up
 * saying "1 years".
 */
const UNIT = { WEEKS: "week", MONTHS: "month", YEARS: "year" };

export function petAge(value, unit) {
  if (value == null) return "";
  const word = UNIT[unit] ?? "year";
  return `${value} ${word}${value === 1 ? "" : "s"}`;
}

/** The same, with "old" on the end, for a sentence rather than a chip. */
export const petAgeLong = (value, unit) =>
  value == null ? "" : `${petAge(value, unit)} old`;
