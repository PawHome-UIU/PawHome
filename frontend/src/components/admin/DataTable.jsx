import Card from "../ui/Card";
import Pagination from "../ui/Pagination";
import { usePaged } from "../../lib/usePaged";

/**
 * Tables are the admin portal's main content type, so the scroll container and
 * the header treatment live here once.
 *
 * `columns` is [{ key, label, align?, className? }]; `rows` is an array of
 * objects; `render` lets a cell return JSX without the table knowing anything
 * about badges or avatars.
 *
 * THE TITLE BAR IS THE DESIGN'S FILLED PURPLE, the same treatment as every
 * other panel header in the product -- Modal, PanelCard, the portal sidebar --
 * and drawn from the token the palette file annotates as "Sidebar: role nav,
 * filled headers". It was a white strip with a hairline under it, which made
 * the admin tables the only panels in the app not wearing their own header.
 *
 * An `action` placed in it now sits on purple: pass a Button with variant
 * "white" or "outline". A "ghost" one is purple-on-purple.
 *
 * PAGINATION IS OPT-IN. Pass `pageSize` and the table paginates; leave it off
 * and every existing caller behaves exactly as before. That is deliberate --
 * a shared component that changes behaviour for tables nobody asked about is
 * how an unrelated page quietly starts hiding rows.
 *
 * THE PAGER ITSELF NOW LIVES IN components/ui/Pagination, NOT HERE. It used to
 * be inlined in this file, which is exactly why the app had paging on the
 * admin tables and nowhere else: every hand-rolled table -- the staff bills,
 * the daycare subscriptions, the adopter's own slips -- would have needed its
 * own copy. One implementation means the pager under a table and the pager
 * under a list cannot drift.
 */
export default function DataTable({
  title, action, columns, rows, render,
  empty = "Nothing to show yet.",
  pageSize = 0,
  resetKey,
}) {
  // resetKey MATTERS MORE HERE THAN IT LOOKS. Two DataTables rendered in the
  // same position by a ternary -- the logs table and the vaccinations table on
  // the care board, say -- are the same element type to React, so it reuses
  // the instance and the page number survives the tab switch. Passing the tab
  // as resetKey is what makes the two tables behave like two tables.
  const pager = usePaged(rows, pageSize, resetKey);
  const { shown, start } = pager;

  return (
    <Card className="overflow-hidden">
      {(title || action) && (
        <div className="flex flex-wrap items-center justify-between gap-3 bg-pw-sidebar px-5 py-3.5">
          {title && <h2 className="text-[15px] font-semibold text-white">{title}</h2>}
          {action}
        </div>
      )}

      {/* Wide tables scroll inside their own container so the page body never
          scrolls sideways. */}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] text-sm">
          <thead>
            <tr className="bg-pw-input/60">
              {columns.map((c) => (
                <th key={c.key}
                  className={`px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wider text-pw-muted ${c.align === "right" ? "text-right" : ""}`}>
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="px-5 py-12 text-center text-pw-muted">
                  {empty}
                </td>
              </tr>
            ) : (
              shown.map((row, i) => (
                <tr key={row.id ?? start + i} className="border-t border-pw-stroke/30 hover:bg-pw-input/40">
                  {columns.map((c) => (
                    <td key={c.key}
                      className={`px-5 py-3.5 align-middle text-pw-ink ${c.align === "right" ? "text-right tabular-nums" : ""} ${c.className ?? ""}`}>
                      {render?.(row, c.key) ?? row[c.key]}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <Pagination {...pager.props} />
    </Card>
  );
}
