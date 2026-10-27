/**
 * A card with the filled purple header band, as the staff screens use for
 * "Today's Care Tasks", "Recent Applications" and "Needs Attention".
 *
 * The admin screens use plain white cards with a plain heading. That is a real
 * difference in the design, not an inconsistency: an admin reads dashboards,
 * a staff member works through queues, and the band marks a queue.
 */
export default function PanelCard({ title, icon: Icon, action, children, className = "" }) {
  return (
    <section className={`overflow-hidden rounded-card border border-pw-stroke/50 bg-white ${className}`}>
      <header className="flex items-center justify-between gap-3 bg-pw-sidebar px-5 py-3.5">
        <h2 className="flex items-center gap-2 text-[15px] font-semibold text-white">
          {Icon && <Icon className="h-[18px] w-[18px]" />}
          {title}
        </h2>
        {action}
      </header>
      {children}
    </section>
  );
}
