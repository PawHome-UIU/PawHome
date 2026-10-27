import { FiTrendingUp, FiTrendingDown } from "react-icons/fi";
import Card from "../ui/Card";

/**
 * A hero number. Deliberately NOT a chart: a single value has no shape to plot,
 * and a sparkline behind one number is decoration.
 *
 * `delta` is a signed percentage. Direction is carried by an arrow AND a word,
 * never by colour alone.
 */
export default function StatTile({ icon: Icon, value, label, delta, tone = "brand" }) {
  const up = typeof delta === "number" && delta >= 0;
  const tones = {
    brand: "bg-pw-input text-pw-button",
    good: "bg-green-50 text-green-700",
    warning: "bg-amber-50 text-amber-700",
  }[tone];

  return (
    <Card className="p-5">
      <div className="flex items-start justify-between gap-3">
        {Icon && (
          <span className={`flex h-10 w-10 items-center justify-center rounded-2xl ${tones}`}>
            <Icon className="h-[18px] w-[18px]" />
          </span>
        )}
        {typeof delta === "number" && (
          <span
            className={`inline-flex items-center gap-1 rounded-pill px-2 py-1 text-xs font-medium ${
              up ? "bg-green-50 text-green-700" : "bg-rose-50 text-rose-700"
            }`}
          >
            {up ? <FiTrendingUp className="h-3 w-3" /> : <FiTrendingDown className="h-3 w-3" />}
            {up ? "+" : ""}{delta}%
            <span className="sr-only">{up ? "increase" : "decrease"} on last month</span>
          </span>
        )}
      </div>

      <p className="mt-4 text-3xl font-semibold tabular-nums tracking-tight text-pw-ink">{value}</p>
      <p className="mt-1 text-sm text-pw-muted">{label}</p>
    </Card>
  );
}
