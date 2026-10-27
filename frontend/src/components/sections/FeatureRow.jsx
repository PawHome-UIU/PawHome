import { FiCheckCircle } from "react-icons/fi";

/**
 * The alternating image / text row used down the Features and Services pages.
 * `flip` puts the image on the right. On small screens the image always comes
 * FIRST regardless of flip, because a wall of text above a picture reads worse
 * than a picture above text.
 */
export default function FeatureRow({ eyebrow, title, points = [], body, flip = false, children }) {
  return (
    <div className="grid items-center gap-10 lg:grid-cols-2">
      <div className={`aspect-[4/3] rounded-card bg-pw-box ${flip ? "lg:order-2" : ""}`} />
      <div className={flip ? "lg:order-1" : ""}>
        {eyebrow && (
          <p className="text-xs font-semibold uppercase tracking-wider text-pw-button">{eyebrow}</p>
        )}
        <h3 className="mt-2 text-2xl font-semibold leading-tight sm:text-[1.75rem]">{title}</h3>
        {body && <p className="mt-4 text-[15px] leading-relaxed text-pw-body">{body}</p>}
        {points.length > 0 && (
          <ul className="mt-5 space-y-3">
            {points.map((p) => (
              <li key={p} className="flex gap-2.5 text-[15px] leading-relaxed text-pw-body">
                <FiCheckCircle className="mt-0.5 h-5 w-5 shrink-0 text-pw-button" />
                {p}
              </li>
            ))}
          </ul>
        )}
        {children}
      </div>
    </div>
  );
}
