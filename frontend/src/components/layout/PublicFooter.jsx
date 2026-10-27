import { Link } from "react-router-dom";
import { FiShare2, FiMail, FiMapPin } from "react-icons/fi";
import Logo from "../brand/Logo";

const COLUMNS = [
  { title: "Product", links: ["Features", "Services", "Pricing", "Success Stories"] },
  { title: "Company", links: ["About", "Contact", "Careers", "Blog"] },
];

export default function PublicFooter() {
  return (
    <footer className="border-t border-pw-stroke/40 bg-pw-tint">
      <div className="mx-auto grid max-w-6xl gap-10 px-5 py-14 sm:px-8 md:grid-cols-4">
        <div className="md:col-span-1">
          <Logo />
          <p className="mt-4 max-w-xs text-sm leading-relaxed text-pw-muted">
            Empowering shelters and adopters to create lifelong bonds through
            technology and empathy.
          </p>
        </div>

        {COLUMNS.map((col) => (
          <div key={col.title}>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-pw-ink">
              {col.title}
            </h3>
            <ul className="mt-4 space-y-2.5">
              {col.links.map((l) => (
                <li key={l}>
                  <Link to="/" className="text-sm text-pw-muted hover:text-pw-button">{l}</Link>
                </li>
              ))}
            </ul>
          </div>
        ))}

        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wider text-pw-ink">Connect</h3>
          <div className="mt-4 flex gap-3">
            {[FiShare2, FiMail, FiMapPin].map((Icon, i) => (
              <span key={i}
                className="flex h-9 w-9 items-center justify-center rounded-full bg-pw-box text-pw-button">
                <Icon className="h-4 w-4" />
              </span>
            ))}
          </div>
        </div>
      </div>

      <div className="border-t border-pw-stroke/40 py-5 text-center text-xs text-pw-muted">
        © {new Date().getFullYear()} PawHome. All rights reserved.
      </div>
    </footer>
  );
}
