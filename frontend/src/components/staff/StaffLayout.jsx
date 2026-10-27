import { FiGrid, FiHeart, FiFileText, FiPlusSquare, FiHome, FiMapPin } from "react-icons/fi";
import PortalShell from "../portal/PortalShell";

const NAV = [
  { to: "/staff", label: "Dashboard", icon: FiGrid, end: true },
  { to: "/staff/pets", label: "Pets", icon: FiHeart },
  { to: "/staff/applications", label: "Applications", icon: FiFileText },
  { to: "/staff/care", label: "Care & Health", icon: FiPlusSquare },
  // One entry, three tabs -- same as the adopter side. See SectionTabs.
  { to: "/staff/boarding", label: "Boarding", icon: FiHome },
  { to: "/staff/shelter", label: "My Shelter", icon: FiMapPin },
];

export default function StaffLayout(props) {
  return (
    <PortalShell
      nav={NAV}
      roleLabel="SHELTER STAFF"
      search="Search pets, applications…"
      {...props}
    />
  );
}
