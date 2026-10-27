import {
  FiGrid, FiHeart, FiTarget, FiFileText, FiHome, FiStar, FiCalendar,
} from "react-icons/fi";
import PortalShell from "../portal/PortalShell";

const NAV = [
  { to: "/app", label: "Dashboard", icon: FiGrid, end: true },
  { to: "/app/pets", label: "Shelters Pets", icon: FiHeart },
  { to: "/app/recommendations", label: "Recommendations", icon: FiTarget },
  { to: "/app/applications", label: "My Applications", icon: FiFileText },
  { to: "/app/my-pets", label: "My Pets", icon: FiHome },
  // ONE ENTRY, THREE TABS. Daycare and Bills used to sit here as siblings,
  // which put three rows in the sidebar for what is one errand -- leaving your
  // pet somewhere, and what it cost. They are tabs inside Boarding now; see
  // components/portal/SectionTabs.
  { to: "/app/boarding", label: "Boarding", icon: FiCalendar },
  { to: "/app/favorites", label: "Favorites", icon: FiStar },
];

export default function AdopterLayout(props) {
  return (
    <PortalShell
      nav={NAV}
      roleLabel="ADOPTER"
      search="Search for your future best friend…"
      {...props}
    />
  );
}
