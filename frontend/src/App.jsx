import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './hooks/useAuth';
import AppLayout from './components/layout/AppLayout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import IntelligencePage from './pages/IntelligencePage';
import DocumentsPage from './pages/DocumentsPage';
import ComparePage from './pages/ComparePage';
import RiskAssessmentPage from './pages/RiskAssessmentPage';
import AuditLogPage from './pages/AuditLogPage';

export default function App() {
  const { user } = useAuth();

  if (!user) return <LoginPage />;

  const isPartnerOrAdmin = user.role === 'ROLE_PARTNER' || user.role === 'ROLE_ADMIN';

  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/intelligence" element={<IntelligencePage />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/compare" element={<ComparePage />} />
        <Route path="/risk" element={<RiskAssessmentPage />} />
        {isPartnerOrAdmin && <Route path="/audit" element={<AuditLogPage />} />}
        <Route path="*" element={<Navigate to="/" />} />
      </Routes>
    </AppLayout>
  );
}
