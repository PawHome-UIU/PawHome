import Button from "../ui/Button";

/**
 * The purple call-to-action band. The design uses it two ways: centred and
 * tall on the landing page, split left/right and slim on Features, Services
 * and Browse. One component, one `layout` prop.
 */
export default function CtaBand({ title, body, action, to, layout = "center" }) {
  if (layout === "split") {
    return (
      <section className="bg-pw-sidebar">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-6 px-5 py-10 sm:px-8">
          <div>
            <h2 className="text-2xl font-semibold text-white sm:text-[1.75rem]">{title}</h2>
            {body && <p className="mt-2 max-w-xl text-[15px] text-white/85">{body}</p>}
          </div>
          <Button to={to} variant="white" size="lg">{action}</Button>
        </div>
      </section>
    );
  }

  return (
    <section className="bg-pw-sidebar">
      <div className="mx-auto max-w-3xl px-5 py-20 text-center sm:px-8">
        <h2 className="text-3xl font-semibold text-white sm:text-4xl">{title}</h2>
        {body && <p className="mx-auto mt-4 max-w-xl text-[15px] leading-relaxed text-white/85">{body}</p>}
        <Button to={to} variant="white" size="lg" className="mt-8">{action}</Button>
      </div>
    </section>
  );
}
