import PublicNav from "./PublicNav";
import PublicFooter from "./PublicFooter";

/**
 * Chrome shared by every public page. Extracted the moment there was a second
 * page -- otherwise each one imports and orders the nav and footer itself, and
 * they drift apart.
 */
export default function PublicLayout({ children }) {
  return (
    <div className="flex min-h-screen flex-col">
      <PublicNav />
      <main className="flex-1">{children}</main>
      <PublicFooter />
    </div>
  );
}
