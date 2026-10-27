/**
 * The white rounded panel used for every content block in the design.
 * `tinted` switches to the pale "Box" fill used by the boarding promo and the
 * selected role card.
 */
export default function Card({ tinted = false, selected = false, className = "", children, ...rest }) {
  return (
    <div
      className={[
        "rounded-card border transition-colors",
        tinted ? "bg-pw-box" : "bg-white",
        selected ? "border-pw-button shadow-card" : "border-pw-stroke/50",
        className,
      ].join(" ")}
      {...rest}
    >
      {children}
    </div>
  );
}
