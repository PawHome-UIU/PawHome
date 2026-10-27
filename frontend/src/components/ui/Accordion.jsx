import { useState } from "react";
import { FiChevronDown } from "react-icons/fi";

/** FAQ list. One item open at a time, matching the Services design. */
export default function Accordion({ items }) {
  const [open, setOpen] = useState(0);

  return (
    <div className="space-y-3">
      {items.map((item, i) => {
        const isOpen = open === i;
        return (
          <div key={item.q} className="overflow-hidden rounded-card border border-pw-stroke/50 bg-white">
            <h3>
              <button
                type="button"
                onClick={() => setOpen(isOpen ? -1 : i)}
                aria-expanded={isOpen}
                className="flex w-full items-center justify-between gap-4 px-5 py-4 text-left"
              >
                <span className="text-[15px] font-medium text-pw-ink">{item.q}</span>
                <FiChevronDown
                  className={`h-5 w-5 shrink-0 text-pw-button transition-transform ${isOpen ? "rotate-180" : ""}`}
                />
              </button>
            </h3>
            {isOpen && (
              <p className="px-5 pb-5 text-[15px] leading-relaxed text-pw-body">{item.a}</p>
            )}
          </div>
        );
      })}
    </div>
  );
}
