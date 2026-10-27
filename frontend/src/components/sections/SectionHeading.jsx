/** Eyebrow + title + optional subtitle, centred or left aligned. */
export default function SectionHeading({ eyebrow, title, sub, align = "center", className = "" }) {
  const centred = align === "center";
  return (
    <div className={`${centred ? "text-center" : ""} ${className}`}>
      {eyebrow && (
        <span className="inline-block rounded-pill bg-pw-input px-3 py-1 text-xs font-medium text-pw-button">
          {eyebrow}
        </span>
      )}
      <h2 className={`${eyebrow ? "mt-4" : ""} text-3xl font-semibold leading-tight sm:text-[2rem]`}>
        {title}
      </h2>
      {sub && (
        <p className={`mt-3 text-[15px] leading-relaxed text-pw-body ${centred ? "mx-auto max-w-2xl" : "max-w-xl"}`}>
          {sub}
        </p>
      )}
    </div>
  );
}
