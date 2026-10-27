import { useId } from "react";
import { FiChevronDown, FiUploadCloud } from "react-icons/fi";

/**
 * Form primitives for the create/edit modals.
 *
 * Input already exists and owns the pill text field. These are the other four
 * shapes the Add Pet / Add Shelter / Add Vaccination / Add Question frames use.
 * They live in one file because they share the label-and-control anatomy: pull
 * them apart and the labels drift out of alignment between modals.
 *
 * Every control here takes its label as a prop and wires htmlFor/id itself.
 * A placeholder is not a label -- it disappears the moment someone types, and
 * screen readers announce the field as unnamed.
 */

function Label({ htmlFor, children, required }) {
  if (!children) return null;
  return (
    <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-pw-ink">
      {children}
      {required && <span className="ml-0.5 text-pw-danger" aria-hidden="true">*</span>}
    </label>
  );
}

const BOX =
  "w-full rounded-card border border-pw-stroke/60 bg-pw-input px-5 text-[15px] text-pw-ink " +
  "placeholder:text-pw-muted focus:border-pw-button focus:outline-none focus:ring-2 focus:ring-pw-button/25";

export function Select({ label, required, className = "", children, ...rest }) {
  const id = useId();
  return (
    <div className={className}>
      <Label htmlFor={id} required={required}>{label}</Label>
      <div className="relative">
        <select
          id={id}
          className={`${BOX} h-12 appearance-none rounded-pill pr-11`}
          {...rest}
        >
          {children}
        </select>
        <FiChevronDown className="pointer-events-none absolute right-4 top-1/2 h-4 w-4 -translate-y-1/2 text-pw-muted" />
      </div>
    </div>
  );
}

export function Textarea({ label, required, rows = 4, className = "", ...rest }) {
  const id = useId();
  return (
    <div className={className}>
      <Label htmlFor={id} required={required}>{label}</Label>
      <textarea id={id} rows={rows} className={`${BOX} resize-none py-3.5`} {...rest} />
    </div>
  );
}

/**
 * The two- or three-way pill switch: Male/Female, Completed/Scheduled/Overdue,
 * Eligibility/Personality.
 *
 * Rendered as real radios in a fieldset rather than buttons. It looks like a
 * toggle but it is a choice between exclusive values, and arrow-key navigation
 * comes free with the right element.
 */
export function Segmented({ label, name, value, onChange, options, className = "" }) {
  return (
    <fieldset className={className}>
      {label && (
        <legend className="mb-1.5 text-sm font-medium text-pw-ink">{label}</legend>
      )}
      <div className="flex rounded-pill bg-pw-input p-1">
        {options.map((o) => {
          const val = o.value ?? o;
          const text = o.label ?? o;
          const on = value === val;
          return (
            <label
              key={val}
              className={[
                "flex-1 cursor-pointer rounded-pill px-4 py-2.5 text-center text-sm transition-colors",
                on ? "bg-white font-medium text-pw-button shadow-card" : "text-pw-body hover:text-pw-ink",
              ].join(" ")}
            >
              <input
                type="radio"
                name={name}
                value={val}
                checked={on}
                onChange={() => onChange(val)}
                className="sr-only"
              />
              {text}
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}

/**
 * Upload target.
 *
 * Wraps a real <input type="file"> rather than a div with a click handler, so
 * it works from the keyboard and the browser's own file picker does the work.
 * No upload happens yet -- the chosen file name is shown and held in the form
 * state, and POSTing it is the API increment's job.
 */
export function FileDrop({
  label, hint, accept, multiple, value, onChange, className = "", compact = false,
}) {
  const id = useId();
  const names = value?.length ? [...value].map((f) => f.name).join(", ") : null;

  // `compact` exists so a file field can share a row with an Input without the
  // row looking broken. The full drop zone is roughly 110px tall against an
  // Input's 72px, so side by side the two never line up -- the shelter form
  // was papering over it with a [&_label]:py-4 override that also squashed the
  // field's own label. Compact matches the Input exactly: same h-12, same pill,
  // same border radius. Everything stays on one line, hint included.
  return (
    <div className={className}>
      <Label htmlFor={id}>{label}</Label>
      <label
        htmlFor={id}
        className={compact
          ? "flex h-12 w-full cursor-pointer items-center gap-2.5 rounded-pill border border-dashed border-pw-stroke bg-pw-input/50 px-5 text-left transition-colors hover:border-pw-button hover:bg-pw-input"
          : "flex cursor-pointer flex-col items-center gap-1.5 rounded-card border-2 border-dashed border-pw-stroke bg-pw-input/50 px-5 py-7 text-center hover:border-pw-button hover:bg-pw-input"}
      >
        <FiUploadCloud className={compact
          ? "h-5 w-5 shrink-0 text-pw-button"
          : "h-7 w-7 text-pw-button"} />
        <span className={compact
          ? "truncate text-[15px] text-pw-ink"
          : "text-[15px] font-medium text-pw-ink"}>
          {names || (compact ? (hint ?? "Choose a file") : "Click to upload or drag & drop")}
        </span>
        {/* In compact mode the hint IS the inner text, so repeating it below
            would put the field back out of alignment. */}
        {!compact && hint && !names && <span className="text-sm text-pw-muted">{hint}</span>}
      </label>
      <input
        id={id}
        type="file"
        accept={accept}
        multiple={multiple}
        onChange={(e) => onChange?.(e.target.files)}
        className="sr-only"
      />
    </div>
  );
}

/**
 * Multi-select chips, as on Add Pet's Personality Traits.
 *
 * The seven traits come from the traits table, which is why this takes a list
 * rather than hard-coding them -- the admin can add an eighth.
 */
/**
 * `options` may be plain strings or {value, label} objects, the same shape
 * Dropdown accepts. The object form exists because the real chips are rows of
 * the `traits` table: the LABEL is what a person reads and can be renamed, the
 * ID is what the join table stores. Selecting by label would detach every
 * mapping the first time somebody renames "Friendly" to "Sociable".
 */
export function ChipGroup({ label, options, value = [], onChange, className = "" }) {
  const toggle = (t) =>
    onChange(value.includes(t) ? value.filter((x) => x !== t) : [...value, t]);

  return (
    <fieldset className={className}>
      {label && <legend className="mb-1.5 text-sm font-medium text-pw-ink">{label}</legend>}
      <div className="flex flex-wrap gap-2">
        {options.map((o) => {
          const t = o?.value ?? o;
          const text = o?.label ?? o;
          const on = value.includes(t);
          return (
            <button
              key={t}
              type="button"
              role="checkbox"
              aria-checked={on}
              onClick={() => toggle(t)}
              className={[
                "rounded-pill border px-4 py-2 text-sm transition-colors",
                on
                  ? "border-pw-button bg-pw-sidebar/35 font-medium text-pw-ink"
                  : "border-pw-stroke/60 bg-white text-pw-body hover:border-pw-stroke",
              ].join(" ")}
            >
              {text}
            </button>
          );
        })}
      </div>
    </fieldset>
  );
}
