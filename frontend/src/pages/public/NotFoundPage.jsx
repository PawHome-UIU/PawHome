import { useLocation } from "react-router-dom";
import { FiSearch } from "react-icons/fi";
import PublicLayout from "../../components/layout/PublicLayout";
import Button from "../../components/ui/Button";
import PawIcon from "../../components/brand/PawIcon";

/**
 * 404.
 *
 * The catch-all used to `<Navigate to="/" replace />`, which silently swallowed
 * every typo and every dead link: you clicked something, landed on the home
 * page, and had no idea whether you had mis-clicked or the app was broken. It
 * also hid a real bug for a while -- /forgot-password had no route, and the
 * redirect made that look like a working link.
 *
 * A 404 that names the path it could not find is a bug report the user can read
 * back to you.
 */
export default function NotFoundPage() {
  const { pathname } = useLocation();

  return (
    <PublicLayout>
      <section className="mx-auto flex max-w-xl flex-col items-center px-5 py-24 text-center sm:px-8">
        <span className="flex h-20 w-20 items-center justify-center rounded-full bg-pw-input text-pw-button">
          <PawIcon className="h-9 w-9" />
        </span>

        <h1 className="mt-8 text-4xl font-semibold tracking-tight sm:text-5xl">
          This page has wandered off
        </h1>
        <p className="mt-4 text-[15px] leading-relaxed text-pw-body">
          We couldn't find anything at{" "}
          <code className="rounded bg-pw-input px-1.5 py-0.5 text-sm text-pw-button">
            {pathname}
          </code>
          . It may have moved, or the link may be out of date.
        </p>

        <div className="mt-9 flex flex-wrap justify-center gap-3">
          <Button to="/" size="lg">Back to home</Button>
          <Button to="/pets" variant="outline" size="lg">
            <FiSearch className="h-4 w-4" /> Browse pets
          </Button>
        </div>
      </section>
    </PublicLayout>
  );
}
