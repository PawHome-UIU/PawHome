// The mark from the Figma header: four toes over a pad.
// Inline SVG rather than an icon-font import, so it inherits currentColor and
// costs no network request on the landing page's first paint.
export default function PawIcon({ className = "h-6 w-6" }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className} aria-hidden="true">
      <ellipse cx="6.2" cy="9.4" rx="2.5" ry="3.1" />
      <ellipse cx="11.4" cy="6.6" rx="2.6" ry="3.3" />
      <ellipse cx="17" cy="8.2" rx="2.4" ry="3" />
      <path d="M12.1 12.4c2.6 0 5.4 2.1 5.4 4.6 0 2-1.6 3.1-3.6 3.1-1 0-1.4-.3-2.4-.3s-1.4.3-2.4.3c-2 0-3.6-1.1-3.6-3.1 0-2.5 2.8-4.6 5.4-4.6z" />
    </svg>
  );
}
