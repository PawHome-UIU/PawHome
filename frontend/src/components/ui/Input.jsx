import { useId, useState } from "react";
import { FiEye, FiEyeOff, FiAlertCircle } from "react-icons/fi";

/**
 * Labelled text field with the design's pill shape and its error state
 * (red ring + icon + message beneath).
 *
 * The password variant owns its own show/hide state: lifting it to the page
 * would mean every form re-implements the same toggle.
 */
export default function Input({
  label,
  type = "text",
  error,
  hint,
  className = "",
  ...rest
}) {
  const id = useId();
  const [reveal, setReveal] = useState(false);
  const isPassword = type === "password";
  const resolvedType = isPassword && reveal ? "text" : type;

  return (
    <div className={className}>
      {label && (
        <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-pw-ink">
          {label}
        </label>
      )}

      <div className="relative">
        <input
          id={id}
          type={resolvedType}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-err` : hint ? `${id}-hint` : undefined}
          className={[
            "h-12 w-full rounded-pill bg-pw-input px-5 text-[15px] text-pw-ink",
            "placeholder:text-pw-muted transition-colors",
            isPassword ? "pr-12" : "",
            error
              ? "border border-pw-danger focus:border-pw-danger"
              : "border border-pw-stroke/60 focus:border-pw-button",
            "focus:outline-none focus:ring-2 focus:ring-pw-button/25",
          ].join(" ")}
          {...rest}
        />

        {isPassword && (
          <button
            type="button"
            onClick={() => setReveal((v) => !v)}
            className="absolute right-4 top-1/2 -translate-y-1/2 text-pw-muted hover:text-pw-button"
            aria-label={reveal ? "Hide password" : "Show password"}
          >
            {reveal ? <FiEyeOff className="h-5 w-5" /> : <FiEye className="h-5 w-5" />}
          </button>
        )}
      </div>

      {error && (
        <p id={`${id}-err`} className="mt-1.5 flex items-center gap-1.5 text-sm text-pw-danger">
          <FiAlertCircle className="h-4 w-4 shrink-0" />
          {error}
        </p>
      )}
      {!error && hint && (
        <p id={`${id}-hint`} className="mt-1.5 text-sm text-pw-muted">{hint}</p>
      )}
    </div>
  );
}
