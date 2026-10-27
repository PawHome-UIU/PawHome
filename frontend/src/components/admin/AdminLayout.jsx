import { FiGrid, FiHome, FiUsers, FiBarChart2, FiFileText, FiHelpCircle } from "react-icons/fi";
import PortalShell from "../portal/PortalShell";

const NAV = [
  { to: "/admin", label: "Dashboard", icon: FiGrid, end: true },
  { to: "/admin/shelters", label: "Shelters", icon: FiHome },
  { to: "/admin/users", label: "Users", icon: FiUsers },
  { to: "/admin/analytics", label: "Analytics", icon: FiBarChart2 },
  { to: "/admin/reports", label: "Reports", icon: FiFileText },
  // V19. The three AdminSlipController endpoints had shipped and nothing
  // called them, so the person who owns the network could see no bills at all.
  { to: "/admin/billing", label: "Billing", icon: FiFileText },
  { to: "/admin/quiz", label: "Quiz Questions", icon: FiHelpCircle },
];

export default function AdminLayout(props) {
  return (
    <PortalShell
      nav={NAV}
      roleLabel="ADMIN PORTAL"
      search="Search shelters, staff, or pets…"
      {...props}
    />
  );
}
