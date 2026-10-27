import { FiCheck, FiCircle } from "react-icons/fi";
import { RULES } from "../../lib/passwordPolicy";

/**
 * The live checklist under a password field.
 *
 * Shows every rule from the start, ticking them off as they are met. The
 * alternative -- one error at a time, after submitting -- makes someone
 * discover the requirements by failing three times in a row.
 *
 * aria-live is "polite" and the list is not a form error: a screen reader
 * should hear a rule pass, not be interrupted mid-word on every keystroke.
 */
export default function PasswordChecklist({ password = "", className = "" }) {
  if (!password) return null;

  return (
    <ul
      aria-live="polite"
      className={`mt-2.5 grid gap-1.5 sm:grid-cols-2 ${className}`}
    >
      {RULES.map(({ id, label, test }) => {
        const met = test(password);
        return (
          <li
            key={id}
            className={`flex items-center gap-1.5 text-xs ${
              met ? "text-pw-ok" : "text-pw-muted"
            }`}
          >
            {met
              ? <FiCheck className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              : <FiCircle className="h-3 w-3 shrink-0" aria-hidden="true" />}
            {/* The state is in the text, not only the colour and icon --
                a red/green pair alone is unreadable to a lot of people. */}
            <span>{label}{met ? " — done" : ""}</span>
          </li>
        );
      })}
    </ul>
  );
}
