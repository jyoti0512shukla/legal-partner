import { useEffect } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
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
import DocumentEditorPage from './pages/DocumentEditorPage';
import AcceptInvitePage from './pages/AcceptInvitePage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';

export default function App() {
  const { user, loading } = useAuth();

  // Initialize theme on mount (before any render)
  useEffect(() => {
    const saved = localStorage.getItem('contractiq-theme') || 'light';
    document.documentElement.setAttribute('data-theme', saved);
  }, []);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg">
        <div className="animate-pulse text-text-muted">Loading...</div>
      </div>
    );
  }

  // Public routes work regardless of auth state
  const location = useLocation();
  const isPublicRoute = location.pathname.startsWith('/invite/') ||
    location.pathname.startsWith('/reset-password/') ||
    location.pathname === '/forgot-password';

  if (isPublicRoute) {
    return (
      <Routes>
        <Route path="/invite/:token" element={<AcceptInvitePage />} />
        <Route path="/reset-password/:token" element={<ResetPasswordPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      </Routes>
    );
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
        <Route path="/documents/:id/edit" element={<DocumentEditorPage />} />
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
