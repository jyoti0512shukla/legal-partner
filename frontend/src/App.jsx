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
import RiskAssessmentPage from './pages/RiskAssessmentPage';
import AuditLogPage from './pages/AuditLogPage';
import MattersPage from './pages/MattersPage';
import ExtractionPage from './pages/ExtractionPage';
import ContractReviewPage from './pages/ContractReviewPage';

export default function App() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg">
        <div className="animate-pulse text-text-muted">Loading...</div>
      </div>
    );
  }

  if (!user) {
    return (
      <Routes>
        <Route path="/" element={<LoginPage />} />
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/change-password" element={<ChangePasswordPage />} />
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
        <Route path="/risk" element={<RiskAssessmentPage />} />
        <Route path="/matters" element={<MattersPage />} />
        <Route path="/extraction" element={<ExtractionPage />} />
        <Route path="/review" element={<ContractReviewPage />} />
        {isPartnerOrAdmin && <Route path="/audit" element={<AuditLogPage />} />}
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/change-password" element={<ChangePasswordPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
}
