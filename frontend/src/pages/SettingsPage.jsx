import { useState, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { setupMfa, verifyMfa, disableMfa } from '../api/auth';
import { AlertCircle, Loader2, Save } from 'lucide-react';
import IntegrationsTab from '../components/IntegrationsTab';
import AgentConfigTab from '../components/AgentConfigTab';
import UserManagementTab from '../components/UserManagementTab';
import TeamsManagementTab from '../components/TeamsManagementTab';
import api from '../api/client';
import AuditLogPage from './AuditLogPage';
import ReviewPipelinesTab from '../components/ReviewPipelinesTab';

export default function SettingsPage() {
  const { user, refreshUser } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [activeTab, setActiveTab] = useState(searchParams.get('tab') || 'profile');
  const [mfaSetup, setMfaSetup] = useState(null);
  const [mfaCode, setMfaCode] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const isAdmin = user?.role === 'ROLE_ADMIN';
  const isPartnerOrAdmin = user?.role === 'ROLE_PARTNER' || isAdmin;

  const TABS = [
    { id: 'profile', label: 'Profile' },
    ...(isPartnerOrAdmin ? [
      { id: 'organization', label: 'Organization' },
      { id: 'integrations', label: 'Integrations' },
      { id: 'agent', label: 'Deal Intelligence' },
      { id: 'pipelines', label: 'Review Pipelines' },
      { id: 'audit', label: 'Audit Log' },
    ] : []),
    ...(isAdmin ? [
      { id: 'users', label: 'Users' },
      { id: 'teams', label: 'Teams' },
    ] : []),
  ];

  useEffect(() => {
    const tab = searchParams.get('tab');
    if (tab) setActiveTab(tab);
    const connected = searchParams.get('connected');
    if (connected) {
      setSuccess(`${connected} connected successfully`);
      setSearchParams({});
      setActiveTab('integrations');
    }
  }, [searchParams, setSearchParams]);

  const handleTabChange = (tabId) => {
    setActiveTab(tabId);
    setError('');
    setSuccess('');
  };

  const handleEnableMfa = async () => {
    setError(''); setSuccess('');
    try { setMfaSetup(await setupMfa()); }
    catch (err) { setError(err.response?.data?.message || 'Failed to setup MFA'); }
  };

  const handleVerifyMfa = async (e) => {
    e.preventDefault(); setError(''); setSuccess('');
    try {
      await verifyMfa(mfaCode);
      setSuccess('MFA enabled successfully');
      setMfaSetup(null); setMfaCode(''); refreshUser();
    } catch (err) { setError(err.response?.data?.message || 'Invalid code'); }
  };

  const handleDisableMfa = async () => {
    setError(''); setSuccess('');
    try { await disableMfa(); setSuccess('MFA disabled'); refreshUser(); }
    catch (err) { setError(err.response?.data?.message || 'Failed to disable MFA'); }
  };

  return (
    <div className="p-6 max-w-4xl">
      <h1 className="text-2xl font-bold text-text-primary mb-6">Settings</h1>

      {/* Tab Navigation */}
      <div className="flex gap-1 border-b border-border mb-6 overflow-x-auto">
        {TABS.map(tab => (
          <button key={tab.id} onClick={() => handleTabChange(tab.id)}
            className={`px-4 pb-3 text-sm font-medium border-b-2 transition-colors whitespace-nowrap ${
              activeTab === tab.id
                ? 'border-primary text-primary'
                : 'border-transparent text-text-muted hover:text-text-primary'
            }`}>
            {tab.label}
          </button>
        ))}
      </div>

      {error && <div className="flex items-center gap-2 text-danger text-sm mb-4"><AlertCircle className="w-4 h-4" /> {error}</div>}
      {success && <div className="text-success text-sm mb-4">{success}</div>}

      {/* ── Profile Tab (all users) ──────────────────────────────────── */}
      {activeTab === 'profile' && (
        <div className="space-y-6">
          {/* User info */}
          <section className="card p-6">
            <h2 className="text-lg font-semibold text-text-primary mb-4">Your Profile</h2>
            <div className="space-y-3 text-sm">
              <div className="flex justify-between"><span className="text-text-muted">Email</span><span className="text-text-primary">{user?.email}</span></div>
              <div className="flex justify-between"><span className="text-text-muted">Name</span><span className="text-text-primary">{user?.displayName || '—'}</span></div>
              <div className="flex justify-between"><span className="text-text-muted">Role</span><span className="text-text-primary">{user?.role?.replace('ROLE_', '')}</span></div>
            </div>
          </section>

          {/* MFA */}
          <section className="card p-6">
            <h2 className="text-lg font-semibold text-text-primary mb-4">Multi-Factor Authentication</h2>
            {mfaSetup ? (
              <div>
                <p className="text-text-muted text-sm mb-4">Scan this QR code with your authenticator app, then enter the 6-digit code.</p>
                <div className="bg-white p-4 rounded-lg inline-block mb-4">
                  <img src={`https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(mfaSetup.qrCodeUrl)}`}
                    alt="MFA QR Code" width={200} height={200} />
                </div>
                <p className="text-xs text-text-muted mb-2">Or enter manually: {mfaSetup.secret}</p>
                <form onSubmit={handleVerifyMfa} className="flex gap-2">
                  <input type="text" placeholder="000000" value={mfaCode}
                    onChange={e => setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                    className="input-field flex-1 max-w-[120px] text-center tracking-widest" maxLength={6} />
                  <button type="submit" className="btn-primary">Verify & Enable</button>
                  <button type="button" onClick={() => { setMfaSetup(null); setMfaCode(''); setError(''); }} className="btn-secondary">Cancel</button>
                </form>
              </div>
            ) : (
              <div className="flex gap-4">
                <button onClick={handleEnableMfa} className="btn-primary">Enable MFA</button>
                {user?.mfaEnabled && <button onClick={handleDisableMfa} className="btn-secondary text-danger">Disable MFA</button>}
              </div>
            )}
          </section>

          {/* Change Password */}
          <section className="card p-6">
            <h2 className="text-lg font-semibold text-text-primary mb-4">Password</h2>
            <Link to="/change-password" className="btn-secondary inline-block">Change Password</Link>
          </section>
        </div>
      )}

      {/* ── Organization Tab (PARTNER/ADMIN only) ──────────────────── */}
      {activeTab === 'organization' && isPartnerOrAdmin && (
        <div className="space-y-6">
          <div>
            <h2 className="text-lg font-semibold text-text-primary mb-1">Organization Settings</h2>
            <p className="text-sm text-text-muted">Security policies and authentication settings for the firm.</p>
          </div>
          {isAdmin && <SecurityConfigSection />}
          {!isAdmin && (
            <p className="text-text-muted text-sm text-center py-6">Contact your administrator to change organization security settings.</p>
          )}
        </div>
      )}

      {activeTab === 'integrations' && <IntegrationsTab />}
      {activeTab === 'agent' && <AgentConfigTab />}
      {activeTab === 'pipelines' && isPartnerOrAdmin && <ReviewPipelinesTab />}

      {activeTab === 'audit' && isPartnerOrAdmin && <AuditLogPage embedded />}

      {activeTab === 'users' && isAdmin && <UserManagementTab />}
      {activeTab === 'teams' && isAdmin && <TeamsManagementTab />}
    </div>
  );
}

// ── Security Config (ADMIN only, shown in Organization tab) ──────────

function SecurityConfigSection() {
  const [config, setConfig] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    api.get('/admin/users/config').then(r => setConfig(r.data)).catch(() => {}).finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    setSaving(true); setSuccess(false);
    try {
      const res = await api.put('/admin/users/config', config);
      setConfig(res.data); setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
    } catch { alert('Failed to save'); }
    finally { setSaving(false); }
  };

  if (loading || !config) return null;

  return (
    <section className="card p-6">
      <h2 className="text-lg font-semibold text-text-primary mb-4">Security Settings</h2>
      <p className="text-sm text-text-muted mb-4">Configure invite, password reset, and lockout policies.</p>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        {[
          ['Invite expiry (hours)', 'inviteExpiryHours'],
          ['Invite resend cooldown (min)', 'inviteResendCooldownMin'],
          ['Password reset expiry (hours)', 'passwordResetExpiryHours'],
          ['Max resets per hour', 'maxPasswordResetsPerHour'],
          ['Max failed logins', 'maxFailedLogins'],
          ['Lockout duration (min)', 'lockoutDurationMinutes'],
        ].map(([label, key]) => (
          <div key={key}>
            <label className="text-xs text-text-muted mb-1 block">{label}</label>
            <input type="number" min="1" value={config[key]}
              onChange={e => setConfig({ ...config, [key]: +e.target.value })}
              className="input-field w-full text-sm" />
          </div>
        ))}
      </div>
      <button onClick={handleSave} disabled={saving} className="btn-primary text-sm mt-4 flex items-center gap-2">
        {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
        {saving ? 'Saving...' : success ? 'Saved!' : 'Save Settings'}
      </button>
    </section>
  );
}
