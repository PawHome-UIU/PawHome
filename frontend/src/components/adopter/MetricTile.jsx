import { Link } from "react-router-dom";
import Card from "../ui/Card";

/**
 * The adopter dashboard stacks its tiles differently from the staff and admin
 * ones: icon, then label, then the number last and large. That is not an
 * inconsistency to normalise away -- an adopter is being reassured ("you have
 * 2 applications running"), while a staff member is scanning a queue, and the
 * label-first order reads as a sentence rather than a metric.
 *
 * `to` MAKES THE WHOLE TILE THE LINK, not a small "view" affordance in the
 * corner. A number on a dashboard is a question -- which two applications? --
 * and the answer is always another page, so the tile is the shortest path to
 * it. Omit `to` and the tile is inert, which is right for a count with nowhere
 * useful to go.
 */
export default function MetricTile({ icon: Icon, label, value, to }) {
  const tile = (
    <Card className={`p-6 ${to ? "h-full transition-shadow hover:shadow-card" : ""}`}>
      <span className="flex h-11 w-11 items-center justify-center rounded-2xl bg-pw-input text-pw-button">
        <Icon className="h-5 w-5" />
      </span>
      <p className="mt-5 text-[15px] text-pw-body">{label}</p>
      <p className="mt-1 text-4xl font-semibold tabular-nums tracking-tight text-pw-button">{value}</p>
    </Card>
  );

  // block, so the anchor fills the grid cell rather than shrink-wrapping the
  // card and leaving a dead strip down the side that looks clickable and is not.
  return to ? <Link to={to} className="block">{tile}</Link> : tile;
}
