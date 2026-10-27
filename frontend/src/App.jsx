import { Routes, Route } from "react-router-dom";
import ProtectedRoute from "./components/auth/ProtectedRoute";
import ScrollToTop from "./components/layout/ScrollToTop";
import LandingPage from "./pages/public/LandingPage";
import BrowsePetsPage from "./pages/public/BrowsePetsPage";
import GuestPetDetailPage from "./pages/public/GuestPetDetailPage";
import NotFoundPage from "./pages/public/NotFoundPage";
import FeaturesPage from "./pages/public/FeaturesPage";
import ServicesPage from "./pages/public/ServicesPage";
import AboutPage from "./pages/public/AboutPage";
import RoleSelectPage from "./pages/auth/RoleSelectPage";
import LoginPage from "./pages/auth/LoginPage";
import RegisterPage from "./pages/auth/RegisterPage";
import ForgotPasswordPage from "./pages/auth/ForgotPasswordPage";
import VerifyEmailPage from "./pages/auth/VerifyEmailPage";
import CheckEmailPage from "./pages/auth/CheckEmailPage";
import ResetPasswordPage from "./pages/auth/ResetPasswordPage";
import AcceptInvitePage from "./pages/auth/AcceptInvitePage";
import SettingsPage from "./pages/SettingsPage";
import AdminDashboard from "./pages/admin/AdminDashboard";
import SheltersPage from "./pages/admin/SheltersPage";
import AnalyticsPage from "./pages/admin/AnalyticsPage";
import QuizQuestionsPage from "./pages/admin/QuizQuestionsPage";
import AdminBillingPage from "./pages/admin/BillingPage";
import ReportsPage from "./pages/admin/ReportsPage";
import UsersPage from "./pages/admin/UsersPage";
import StaffDashboard from "./pages/staff/StaffDashboard";
import PetsManagementPage from "./pages/staff/PetsManagementPage";
import ApplicationsPage from "./pages/staff/ApplicationsPage";
import ApplicationReviewPage from "./pages/staff/ApplicationReviewPage";
import CareHealthPage from "./pages/staff/CareHealthPage";
import BoardingPage from "./pages/staff/BoardingPage";
import ShelterPage from "./pages/staff/ShelterPage";
import AdopterDashboard from "./pages/adopter/AdopterDashboard";
import ShelterPetsPage from "./pages/adopter/ShelterPetsPage";
import PetDetailPage from "./pages/adopter/PetDetailPage";
import AdoptionApplicationPage from "./pages/adopter/AdoptionApplicationPage";
import RecommendationsPage from "./pages/adopter/RecommendationsPage";
import MyApplicationsPage from "./pages/adopter/MyApplicationsPage";
import MyPetsPage from "./pages/adopter/MyPetsPage";
import AdopterBoardingPage from "./pages/adopter/AdopterBoardingPage";
import AdopterDaycarePage from "./pages/adopter/AdopterDaycarePage";
import MySlipsPage from "./pages/adopter/MySlipsPage";
import SlipDetailPage from "./pages/adopter/SlipDetailPage";
import StaffDaycarePage from "./pages/staff/DaycarePage";
import StaffSlipsPage from "./pages/staff/SlipsPage";
import BoardingDetailPage from "./pages/adopter/BoardingDetailPage";
import FavoritesPage from "./pages/adopter/FavoritesPage";

/**
 * Every route in the application, in four groups.
 *
 *   public   /            /pets  /features  /services  /about
 *   auth     /join        /login /register
 *   admin    /admin/*   dashboard, shelters, users, analytics, reports, quiz
 *   staff    /staff/*   dashboard, pets, applications, care, boarding,
 *                         shelter
 *   adopter  /app/*     dashboard, pets, recommendations, applications,
 *                       boarding, favorites
 *
 * /{admin,staff,app}/settings all render the same SettingsPage, which picks its
 * portal chrome from the path prefix.
 *
 * /join is the role picker and comes BEFORE /register, because the design
 * chooses a role first and registration reads it from router state.
 *
 * EVERY PORTAL ROUTE IS BEHIND ProtectedRoute, which is navigation rather than
 * security -- see that file. The real access control is SecurityConfig and
 * ScopeGuard on the server; this only stops someone landing on a portal that
 * will 403 every request it makes.
 */
export default function App() {
  return (
    <>
      {/* Must sit inside the Router and outside Routes: it has to see every
          location change, including ones that swap the whole page. */}
      <ScrollToTop />
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/pets" element={<BrowsePetsPage />} />
        {/* The signed-out profile. PetCard links here, so this route existing
            is what stops every "View Profile" on the public grid from bouncing
            off the catch-all back to the landing page. */}
        <Route path="/pets/:id" element={<GuestPetDetailPage />} />
        <Route path="/features" element={<FeaturesPage />} />
        <Route path="/services" element={<ServicesPage />} />
        <Route path="/about" element={<AboutPage />} />
        <Route path="/join" element={<RoleSelectPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        {/* Both are reached from links in email, by someone not signed in.
            Each is protected by the token in its query string. */}
        {/* Where registration lands: "confirm your address", with a button
            to the right webmail provider. */}
        <Route path="/check-email" element={<CheckEmailPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        {/* Where a staff invitation lands. Public for the same reason the
            reset route is: the person has no account to sign in with yet --
            the token in the link is the whole of their authority. */}
        <Route path="/accept-invite" element={<AcceptInvitePage />} />

        {/* Admin portal. */}
        <Route element={<ProtectedRoute roles={["ADMIN"]} />}>
        <Route path="/admin" element={<AdminDashboard />} />
        <Route path="/admin/shelters" element={<SheltersPage />} />
        <Route path="/admin/analytics" element={<AnalyticsPage />} />
        <Route path="/admin/quiz" element={<QuizQuestionsPage />} />
        <Route path="/admin/billing" element={<AdminBillingPage />} />
        <Route path="/admin/reports" element={<ReportsPage />} />
        <Route path="/admin/users" element={<UsersPage />} />
        <Route path="/admin/settings" element={<SettingsPage />} />
        </Route>

        {/* Shelter staff portal. STAFF ONLY -- an admin is redirected to
            /admin.

            An earlier version let ADMIN in here as "oversight", which was
            wrong. ck_users_staff_shelter means an admin has NO shelter_id,
            and every page in this portal is built around "my shelter": the
            pet list, the application queue, the boarding board. An admin
            landing here would see either nothing or every shelter's rows
            fused together in a UI designed for one. Admin oversight belongs
            on admin pages, which can say which shelter they are showing. */}
        <Route element={<ProtectedRoute roles={["STAFF"]} />}>
        <Route path="/staff" element={<StaffDashboard />} />
        <Route path="/staff/pets" element={<PetsManagementPage />} />
        <Route path="/staff/applications" element={<ApplicationsPage />} />
        <Route path="/staff/applications/:id" element={<ApplicationReviewPage />} />
        <Route path="/staff/care" element={<CareHealthPage />} />
        <Route path="/staff/boarding" element={<BoardingPage />} />
        <Route path="/staff/daycare" element={<StaffDaycarePage />} />
        <Route path="/staff/slips" element={<StaffSlipsPage />} />
        <Route path="/staff/shelter" element={<ShelterPage />} />
        <Route path="/staff/settings" element={<SettingsPage />} />
        </Route>

        {/* Adopter portal. Mounted at /app rather than /adopter so the URL a
            signed-in person lives in stays short. */}
        <Route element={<ProtectedRoute roles={["ADOPTER"]} />}>
        <Route path="/app" element={<AdopterDashboard />} />
        <Route path="/app/pets" element={<ShelterPetsPage />} />
        <Route path="/app/pets/:id" element={<PetDetailPage />} />
        <Route path="/app/pets/:id/apply" element={<AdoptionApplicationPage />} />
        <Route path="/app/recommendations" element={<RecommendationsPage />} />
        <Route path="/app/applications" element={<MyApplicationsPage />} />
        <Route path="/app/my-pets" element={<MyPetsPage />} />
        <Route path="/app/boarding" element={<AdopterBoardingPage />} />
        <Route path="/app/boarding/:id" element={<BoardingDetailPage />} />
        <Route path="/app/daycare" element={<AdopterDaycarePage />} />
        {/* /slips before /slips/:id is irrelevant to react-router 7 -- it
            ranks by specificity, not by order -- but keeping the list and the
            detail adjacent is how the next person finds both. */}
        <Route path="/app/slips" element={<MySlipsPage />} />
        <Route path="/app/slips/:id" element={<SlipDetailPage />} />
        <Route path="/app/favorites" element={<FavoritesPage />} />
        <Route path="/app/settings" element={<SettingsPage />} />
        </Route>

        {/* A real 404, not a redirect home. The old catch-all silently
            swallowed every typo and hid /forgot-password having no route. */}
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </>
  );
}
