const TONES = {
  AVAILABLE: "bg-green-100 text-green-800",
  PENDING: "bg-amber-100 text-amber-800",
  ADOPTED: "bg-pw-input text-pw-button",
  BOARDING: "bg-sky-100 text-sky-800",
};

export default function StatusBadge({ status }) {
  return (
    <span className={`rounded-md px-2 py-1 text-[10px] font-semibold tracking-wide ${TONES[status] ?? TONES.ADOPTED}`}>
      {status}
    </span>
  );
}
