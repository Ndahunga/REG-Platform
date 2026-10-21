import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthLayout } from '@/layouts/AuthLayout'
import { AppLayout } from '@/layouts/AppLayout'
import { SuperAdminLayout } from '@/layouts/SuperAdminLayout'
import { PublicLayout } from '@/layouts/PublicLayout'
import { LandingPage } from '@/pages/LandingPage'
import { AboutPage } from '@/pages/AboutPage'
import { ContactPage } from '@/pages/ContactPage'
import { TermsPage } from '@/pages/TermsPage'
import { LoginPage } from '@/pages/LoginPage'
import { LoginSuccessSplashPage } from '@/pages/LoginSuccessSplashPage'
import { SignupPage } from '@/pages/SignupPage'
import { ForgotPasswordPage } from '@/pages/ForgotPasswordPage'
import { ResetPasswordPage } from '@/pages/ResetPasswordPage'
import { ChangePasswordPage } from '@/pages/ChangePasswordPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { MembersPage } from '@/pages/MembersPage'
import { MemberDetailPage } from '@/pages/MemberDetailPage'
import { ContributionsPage } from '@/pages/ContributionsPage'
import { LoansPage } from '@/pages/LoansPage'
import { LoanDetailRoute } from '@/pages/LoanDetailPage'
import { FinesPage } from '@/pages/FinesPage'
import { FineDetailPage } from '@/pages/FineDetailPage'
import { FinePaymentQueuePage } from '@/pages/FinePaymentQueuePage'
import { SocialFundPage } from '@/pages/SocialFundPage'
import { InvestmentsPage } from '@/pages/InvestmentsPage'
import { InvestmentDetailPage } from '@/pages/InvestmentDetailPage'
import { TransactionsPage } from '@/pages/TransactionsPage'
import { LedgerPage } from '@/pages/LedgerPage'
import { PayoutsPage } from '@/pages/PayoutsPage'
import { PaymentsPage } from '@/pages/PaymentsPage'
import { RotationPage } from '@/pages/RotationPage'
import { PayoutDetailPage } from '@/pages/PayoutDetailPage'
import { ReportsPage } from '@/pages/ReportsPage'
import { HistoricalImportPage } from '@/pages/HistoricalImportPage'
import { SettingsPage } from '@/pages/SettingsPage'
import { BillingPage } from '@/pages/BillingPage'
import { PaymentReturnPage } from '@/pages/PaymentReturnPage'
import { NotificationsPage } from '@/pages/NotificationsPage'
import { AuditLogsPage } from '@/pages/AuditLogsPage'
import { ProfilePage } from '@/pages/ProfilePage'
import { CooperativesPage } from '@/pages/CooperativesPage'
import { CooperativeDetailPage } from '@/pages/CooperativeDetailPage'
import { SuperAdminHomePage } from '@/pages/SuperAdminHomePage'
import { SuperAdminsPage } from '@/pages/SuperAdminsPage'
import { SystemHealthPage } from '@/pages/SystemHealthPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ROUTES, SUPER_ADMIN_ROUTES } from '@/shared/constants/routes'
import {
  FINANCE_ACCESS_ROLES,
  LEADERSHIP_ROLES,
  SECRETARY_ACCESS_ROLES,
} from '@/shared/types/auth'
import { AuthBootstrap } from './AuthBootstrap'
import { ProtectedRoute } from './ProtectedRoute'
import { ClientPortalRoute } from './ClientPortalRoute'
import { RoleRoute } from './RoleRoute'
import { SuperAdminRoute } from './SuperAdminRoute'
import { SupportWorkspaceRoute } from './SupportWorkspaceRoute'

/** The retired standalone privacy page. Kept only so old links still land somewhere useful. */
const LEGACY_PRIVACY_PATH = '/privacy'

export function AppRouter() {
  return (
    <BrowserRouter>
      <AuthBootstrap>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path={ROUTES.home} element={<LandingPage />} />
            <Route path={ROUTES.about} element={<AboutPage />} />
            <Route path={ROUTES.contact} element={<ContactPage />} />
            {/* The standalone privacy page is gone; old links land on the privacy section of the Terms. */}
            <Route path={LEGACY_PRIVACY_PATH} element={<Navigate to={`${ROUTES.terms}#privacy`} replace />} />
            <Route path={ROUTES.terms} element={<TermsPage />} />
          </Route>

          <Route element={<AuthLayout />}>
            <Route path={ROUTES.login} element={<LoginPage />} />
            <Route path={ROUTES.signup} element={<SignupPage />} />
            <Route path={ROUTES.forgotPassword} element={<ForgotPasswordPage />} />
            <Route path={ROUTES.resetPassword} element={<ResetPasswordPage />} />
          </Route>

          <Route element={<ProtectedRoute />}>
            <Route path={ROUTES.loginSuccess} element={<LoginSuccessSplashPage />} />
            <Route element={<ClientPortalRoute />}>
            <Route element={<AppLayout />}>
              <Route path={ROUTES.dashboard} element={<DashboardPage />} />
              <Route element={<RoleRoute roles={SECRETARY_ACCESS_ROLES} />}>
                <Route path={ROUTES.members} element={<MembersPage />} />
                <Route path="/members/:userId" element={<MemberDetailPage />} />
              </Route>
              <Route path={ROUTES.contributions} element={<ContributionsPage />} />
              <Route path="/contributions/special/:campaignId" element={<ContributionsPage />} />
              <Route path={ROUTES.loans} element={<LoansPage />} />
              <Route path="/loans/:loanId" element={<LoanDetailRoute />} />
              <Route path={ROUTES.fines} element={<FinesPage />} />
              <Route path="/fines/:fineId" element={<FineDetailPage />} />
              <Route element={<RoleRoute roles={FINANCE_ACCESS_ROLES} />}>
                <Route path={ROUTES.finePayments} element={<FinePaymentQueuePage />} />
              </Route>
              <Route path={ROUTES.socialFund} element={<SocialFundPage />} />
              <Route element={<RoleRoute roles={FINANCE_ACCESS_ROLES} />}>
                <Route path={ROUTES.investments} element={<InvestmentsPage />} />
                <Route path="/investments/:investmentId" element={<InvestmentDetailPage />} />
                <Route path={ROUTES.transactions} element={<TransactionsPage />} />
                <Route path={ROUTES.ledger} element={<LedgerPage />} />
              </Route>
              <Route path={ROUTES.payouts} element={<PayoutsPage />} />
              <Route path="/payouts/:runId" element={<PayoutDetailPage />} />
              <Route path={ROUTES.payments} element={<PaymentsPage />} />
              <Route path={ROUTES.rotation} element={<RotationPage />} />
              <Route path={ROUTES.reports} element={<ReportsPage />} />
              <Route path={ROUTES.billing} element={<BillingPage />} />
              <Route path={ROUTES.billingPaymentReturn} element={<PaymentReturnPage />} />
              <Route element={<RoleRoute roles={[...LEADERSHIP_ROLES]} />}>
                <Route path={ROUTES.historicalImport} element={<HistoricalImportPage />} />
              </Route>
              <Route path={ROUTES.notifications} element={<NotificationsPage />} />
              <Route path={ROUTES.profile} element={<ProfilePage />} />
              <Route path={ROUTES.changePassword} element={<ChangePasswordPage />} />
              <Route element={<RoleRoute roles={[...LEADERSHIP_ROLES]} />}>
                <Route path={ROUTES.settings} element={<SettingsPage />} />
              </Route>
              <Route element={<RoleRoute roles={SECRETARY_ACCESS_ROLES} />}>
                <Route path={ROUTES.auditLogs} element={<AuditLogsPage />} />
              </Route>
              {/* Legacy platform URLs: ClientPortalRoute sends a SUPER_ADMIN to the portal; everyone else lands on the dashboard. */}
              <Route path={ROUTES.cooperatives} element={<Navigate to={ROUTES.dashboard} replace />} />
              <Route path="/cooperatives/:id" element={<Navigate to={ROUTES.dashboard} replace />} />
              <Route path={ROUTES.system} element={<Navigate to={ROUTES.dashboard} replace />} />
            </Route>
            </Route>

            <Route element={<SuperAdminRoute />}>
              <Route element={<SuperAdminLayout />}>
                <Route path={SUPER_ADMIN_ROUTES.home} element={<SuperAdminHomePage />} />
                <Route path={SUPER_ADMIN_ROUTES.admins} element={<SuperAdminsPage />} />
                <Route path={SUPER_ADMIN_ROUTES.cooperatives} element={<CooperativesPage />} />
                <Route path="/superadmin/cooperatives/:id" element={<CooperativeDetailPage />} />
                {/* Support workspace: the existing Saving Scheme pages, opened by a SUPER_ADMIN inside the portal. */}
                <Route element={<SupportWorkspaceRoute />}>
                  <Route path="/superadmin/cooperatives/:cooperativeId/dashboard" element={<DashboardPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/members" element={<MembersPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/members/:userId" element={<MemberDetailPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/contributions" element={<ContributionsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/contributions/special/:campaignId" element={<ContributionsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/loans" element={<LoansPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/loans/:loanId" element={<LoanDetailRoute />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/fines" element={<FinesPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/fines/:fineId" element={<FineDetailPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/fine-payments" element={<FinePaymentQueuePage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/social-fund" element={<SocialFundPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/investments" element={<InvestmentsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/investments/:investmentId" element={<InvestmentDetailPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/transactions" element={<TransactionsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/ledger" element={<LedgerPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/payouts" element={<PayoutsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/payouts/:runId" element={<PayoutDetailPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/payments" element={<PaymentsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/rotation" element={<RotationPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/reports" element={<ReportsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/billing" element={<BillingPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/billing/payment-return" element={<PaymentReturnPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/historical-import" element={<HistoricalImportPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/settings" element={<SettingsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/audit-logs" element={<AuditLogsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/notifications" element={<NotificationsPage />} />
                  <Route path="/superadmin/cooperatives/:cooperativeId/*" element={<NotFoundPage />} />
                </Route>
                <Route path={SUPER_ADMIN_ROUTES.system} element={<SystemHealthPage />} />
                <Route path={SUPER_ADMIN_ROUTES.profile} element={<ProfilePage />} />
                <Route path={SUPER_ADMIN_ROUTES.changePassword} element={<ChangePasswordPage />} />
                <Route path="/superadmin/*" element={<NotFoundPage />} />
              </Route>
            </Route>
          </Route>

          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </AuthBootstrap>
    </BrowserRouter>
  )
}
