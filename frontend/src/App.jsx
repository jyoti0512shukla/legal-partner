import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './hooks/useAuth';
import AppLayout from './components/layout/AppLayout';
import LoginPage from './pages/LoginPage';
import SignUpPage from './pages/SignUpPage';
import ChangePasswordPage from './pages/ChangePasswordPage';
import SettingsPage from './pages/SettingsPage';
import DashboardPage from './pages/DashboardPage';
import IntelligencePage from './pages/IntelligencePage';
import DocumentsPage from './pages/DocumentsPage';
import DraftPage from './pages/DraftPage';
import ComparePage from './pages/ComparePage';
import AuditLogPage from './pages/AuditLogPage';
import MattersPage from './pages/MattersPage';
import ExtractionPage from './pages/ExtractionPage';
import ContractReviewPage from './pages/ContractReviewPage';
import WorkflowsPage from './pages/WorkflowsPage';
import WorkflowRunPage from './pages/WorkflowRunPage';
import WorkflowBuilderPage from './pages/WorkflowBuilderPage';
import WorkflowAnalyticsPage from './pages/WorkflowAnalyticsPage';
import ClauseLibraryPage from './pages/ClauseLibraryPage';
import EdgarImportPage from './pages/EdgarImportPage';
import PlaybooksPage from './pages/PlaybooksPage';
import MatterDetailPage from './pages/MatterDetailPage';
import AcceptInvitePage from './pages/AcceptInvitePage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';

export default function App() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg">
        <div className="animate-pulse text-text-muted">Loading...</div>
      </div>
    );
  }

  // These routes work regardless of auth state (invite, reset password)
  const publicRoutes = (
    <Routes>
      <Route path="/invite/:token" element={<AcceptInvitePage />} />
      <Route path="/reset-password/:token" element={<ResetPasswordPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
    </Routes>
  );

  // Check if current path is a public route
  const path = window.location.pathname;
  if (path.startsWith('/invite/') || path.startsWith('/reset-password/') || path === '/forgot-password') {
    return publicRoutes;
  }

  if (!user) {
    return (
      <Routes>
        <Route path="/" element={<LoginPage />} />
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/change-password" element={<ChangePasswordPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password/:token" element={<ResetPasswordPage />} />
        <Route path="/invite/:token" element={<AcceptInvitePage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    );
  }

  const isPartnerOrAdmin = user.role === 'ROLE_PARTNER' || user.role === 'ROLE_ADMIN';

  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/intelligence" element={<IntelligencePage />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/draft" element={<DraftPage />} />
        <Route path="/compare" element={<ComparePage />} />
        <Route path="/matters" element={<MattersPage />} />
        <Route path="/matters/:id" element={<MatterDetailPage />} />
        <Route path="/extraction" element={<ExtractionPage />} />
        <Route path="/review" element={<ContractReviewPage />} />
        <Route path="/risk" element={<ContractReviewPage />} />
        <Route path="/workflows" element={<WorkflowsPage />} />
        <Route path="/workflows/run" element={<WorkflowRunPage />} />
        <Route path="/workflows/run/:id" element={<WorkflowRunPage />} />
        <Route path="/workflows/builder" element={<WorkflowBuilderPage />} />
        <Route path="/workflows/analytics" element={<WorkflowAnalyticsPage />} />
        <Route path="/clause-library" element={<ClauseLibraryPage />} />
        {isPartnerOrAdmin && <Route path="/playbooks" element={<PlaybooksPage />} />}
        {isPartnerOrAdmin && <Route path="/edgar-import" element={<EdgarImportPage />} />}
        {isPartnerOrAdmin && <Route path="/audit" element={<AuditLogPage />} />}
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/change-password" element={<ChangePasswordPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
}
