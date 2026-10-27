import Logo from "../brand/Logo";

/**
 * The split screen behind login and registration.
 *
 * In the design the two pages MIRROR each other: login puts the photograph on
 * the left, registration puts it on the right. That is the only difference, so
 * it is one prop rather than two layouts.
 *
 * The panel is hidden below lg -- a decorative half-screen image is the first
 * thing that should go on a phone, and the form must keep the full width.
 */
export default function AuthLayout({
  side = "left",
  title,
  tagline,
  image,
  children,
}) {
  const panel = (
    <aside
      className="relative hidden lg:flex lg:w-1/2 items-center justify-center overflow-hidden"
      aria-hidden="true"
    >
      {image ? (
        <img src={image} alt="" className="absolute inset-0 h-full w-full object-cover" />
      ) : (
        <div className="absolute inset-0 bg-pw-box" />
      )}
      {/* Brand wash. The photograph reads as lavender in every Figma frame,
          which is this overlay rather than a pre-tinted asset -- so any
          photograph dropped in here comes out on-brand. */}
      <div className="absolute inset-0 bg-pw-sidebar/55 mix-blend-multiply" />
      <div className="absolute inset-0 bg-gradient-to-t from-pw-ink/35 via-transparent to-transparent" />

      <div className="relative z-10 max-w-md px-10 text-center">
        <Logo size="lg" tone="white" to={null} />
        <h2 className="mt-6 text-4xl font-semibold leading-tight text-white">{title}</h2>
        {tagline && <p className="mt-3 text-lg text-white/85">{tagline}</p>}
      </div>
    </aside>
  );

  const form = (
    <main className="flex w-full items-center justify-center px-5 py-12 lg:w-1/2 sm:px-8">
      <div className="w-full max-w-[420px]">{children}</div>
    </main>
  );

  return (
    <div className="flex min-h-screen bg-white">
      {side === "left" ? (<>{panel}{form}</>) : (<>{form}{panel}</>)}
    </div>
  );
}
