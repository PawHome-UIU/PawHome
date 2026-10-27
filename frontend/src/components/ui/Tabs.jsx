/** Underlined tab row, as used on the Quiz Questions and Shelter detail pages. */
export default function Tabs({ tabs, value, onChange, className = "" }) {
  return (
    <div className={`flex gap-7 border-b border-pw-stroke/50 ${className}`} role="tablist">
      {tabs.map((t) => {
        const key = t.key ?? t;
        const label = t.label ?? t;
        const active = value === key;
        return (
          <button
            key={key}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(key)}
            className={[
              "-mb-px border-b-2 pb-3 text-[15px] transition-colors",
              active
                ? "border-pw-button font-medium text-pw-button"
                : "border-transparent text-pw-muted hover:text-pw-body",
            ].join(" ")}
          >
            {label}
            {t.count != null && (
              <span className="ml-2 rounded-pill bg-pw-input px-2 py-0.5 text-xs text-pw-button">{t.count}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}
