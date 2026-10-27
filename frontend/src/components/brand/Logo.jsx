import { Link } from "react-router-dom";
import PawIcon from "./PawIcon";

/**
 * The PawHome wordmark. `to` makes it a link; pass to={null} on pages where
 * clicking the logo would be a dead end (the login split panel, for instance).
 */
export default function Logo({ size = "md", tone = "brand", to = "/" }) {
  const sizes = {
    sm: { icon: "h-5 w-5", text: "text-lg" },
    md: { icon: "h-6 w-6", text: "text-xl" },
    lg: { icon: "h-8 w-8", text: "text-3xl" },
  }[size];

  const tones = {
    brand: "text-pw-button",
    white: "text-white",
  }[tone];

  const inner = (
    <span className={`inline-flex items-center gap-2 font-semibold ${tones}`}>
      <PawIcon className={sizes.icon} />
      <span className={`${sizes.text} tracking-tight`}>PawHome</span>
    </span>
  );

  return to ? (
    <Link to={to} className="inline-flex rounded-md" aria-label="PawHome home">
      {inner}
    </Link>
  ) : (
    inner
  );
}
