import { useId } from "react";

export default function Checkbox({ label, className = "", ...rest }) {
  const id = useId();
  return (
    <label htmlFor={id} className={`inline-flex cursor-pointer items-center gap-2.5 ${className}`}>
      <input
        id={id}
        type="checkbox"
        className="h-[18px] w-[18px] shrink-0 cursor-pointer rounded-[5px] border-pw-stroke text-pw-button focus:ring-pw-button/30"
        {...rest}
      />
      <span className="text-sm text-pw-body">{label}</span>
    </label>
  );
}
