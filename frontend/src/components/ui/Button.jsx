import { Link } from "react-router-dom";

/**
 * The one button in this application.
 *
 * Pages that each invent their own button are how a design drifts, so every
 * call site goes through here. Renders <button>, <a> or <Link> depending on
 * what it is given -- a button that navigates must be a link, or keyboard and
 * middle-click both break.
 */
const VARIANTS = {
  primary:
    "bg-pw-button text-white shadow-card hover:bg-pw-hover active:translate-y-px",
  outline:
    "bg-white text-pw-button border border-pw-stroke hover:bg-pw-input",
  ghost: "bg-transparent text-pw-button hover:bg-pw-input",
  white: "bg-white text-pw-button shadow-card hover:bg-pw-input",
  subtle: "bg-pw-box text-pw-ink hover:bg-pw-stroke/40",
};

const SIZES = {
  sm: "h-9 px-4 text-sm",
  md: "h-11 px-6 text-[15px]",
  lg: "h-12 px-7 text-base",
};

export default function Button({
  as,
  to,
  href,
  variant = "primary",
  size = "md",
  full = false,
  disabled = false,
  className = "",
  children,
  ...rest
}) {
  const cls = [
    "inline-flex items-center justify-center gap-2 rounded-pill font-medium",
    "transition-colors duration-150 disabled:opacity-50 disabled:pointer-events-none",
    VARIANTS[variant],
    SIZES[size],
    full ? "w-full" : "",
    className,
  ].join(" ");

  // A disabled link is not a thing. `disabled` on an <a> is an invalid
  // attribute the browser ignores, so "Apply to Adopt" on a PENDING pet would
  // look greyed out and still navigate. Downgrade to a real <button> instead --
  // that is the only element the attribute means anything on, and it also drops
  // the control out of the tab order, which the greying alone does not.
  if ((to || href) && disabled) {
    return <button type="button" disabled className={cls} {...rest}>{children}</button>;
  }

  if (to) return <Link to={to} className={cls} {...rest}>{children}</Link>;
  if (href) return <a href={href} className={cls} {...rest}>{children}</a>;
  const Tag = as || "button";

  // A <button> with no type attribute IS a submit button. Any control inside a
  // form that was not meant to submit -- Cancel, a filter, a preview toggle --
  // therefore submits it. Default to "button" and let the call sites that mean
  // submit say so; `rest` spreads afterwards, so an explicit type still wins.
  const typed = Tag === "button" ? { type: "button" } : {};
  return <Tag className={cls} disabled={disabled} {...typed} {...rest}>{children}</Tag>;
}
