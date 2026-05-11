import { useState, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { setupMfa, verifyMfa, disableMfa, enableEmailMfa, getBackupCodesRemaining, regenerateBackupCodes, getTrustedDevices, revokeAllDevices } from '../api/auth';
import { AlertCircle, Loader2, Save } from 'lucide-react';
import IntegrationsTab from '../components/IntegrationsTab';
import AgentConfigTab from '../components/AgentConfigTab';
import UserManagementTab from '../components/UserManagementTab';
import TeamsManagementTab from '../components/TeamsManagementTab';
import api from '../api/client';
import AuditLogPage from './AuditLogPage';
import ReviewPipelinesTab from '../components/ReviewPipelinesTab';
import DeadlineConfigTab from '../components/contract/DeadlineConfigTab';

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
      { id: 'agent', label: 'AI Agent' },
      { id: 'pipelines', label: 'Review Pipelines' },
      { id: 'audit', label: 'Audit Log' },
    ] : []),
    ...(isAdmin ? [{ id: 'users', label: 'Users' }] : []),
    ...(isPartnerOrAdmin ? [{ id: 'teams', label: 'Teams' }] : []),
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
          <section className="card p-6">
            <h2 className="text-lg font-semibold text-text-primary mb-4">Your Profile</h2>
            <div className="space-y-3 text-sm">
              <div className="flex justify-between"><span className="text-text-muted">Email</span><span className="text-text-primary">{user?.email}</span></div>
              <div className="flex justify-between"><span className="text-text-muted">Name</span><span className="text-text-primary">{user?.displayName || '—'}</span></div>
              <div className="flex justify-between"><span className="text-text-muted">Role</span><span className="text-text-primary">{user?.role?.replace('ROLE_', '')}</span></div>
              <div className="flex justify-between"><span className="text-text-muted">MFA</span><span className={user?.mfaEnabled ? 'text-success' : 'text-text-muted'}>{user?.mfaEnabled ? `Enabled (${user?.mfaMethod || 'TOTP'})` : 'Not enabled'}</span></div>
            </div>
          </section>

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

          {/* MFA — comprehensive */}
          <MfaSection
            user={user}
            mfaSetup={mfaSetup}
            mfaCode={mfaCode}
            setMfaCode={setMfaCode}
            onSetup={handleEnableMfa}
            onVerify={handleVerifyMfa}
            onDisable={handleDisableMfa}
            onCancel={() => { setMfaSetup(null); setMfaCode(''); setError(''); }}
            refreshUser={refreshUser}
            setError={setError}
            setSuccess={setSuccess}
          />

          {isAdmin && <SecurityConfigSection />}
          {!isAdmin && (
            <p className="text-text-muted text-sm text-center py-4">Contact your administrator to change security settings.</p>
          )}

          {/* Deadline Alerts — part of org settings */}
          <section className="card p-6">
            <DeadlineConfigTab />
          </section>
        </div>
      )}

      {activeTab === 'integrations' && <IntegrationsTab />}
      {activeTab === 'agent' && <AgentConfigTab />}
      {activeTab === 'pipelines' && isPartnerOrAdmin && <ReviewPipelinesTab />}

      {activeTab === 'audit' && isPartnerOrAdmin && <AuditLogPage embedded />}

      {activeTab === 'users' && isAdmin && <UserManagementTab />}
      {activeTab === 'teams' && isPartnerOrAdmin && <TeamsManagementTab />}
    </div>
  );
}

// ── MFA Section (shown in Organization tab) ──────────

function MfaSection({ user, mfaSetup, mfaCode, setMfaCode, onSetup, onVerify, onDisable, onCancel, refreshUser, setError, setSuccess }) {
  const [backupRemaining, setBackupRemaining] = useState(null);
  const [backupCodes, setBackupCodes] = useState(null);
  const [devices, setDevices] = useState([]);
  const [loadingDevices, setLoadingDevices] = useState(false);
  const [showDevices, setShowDevices] = useState(false);

  useEffect(() => {
    if (user?.mfaEnabled) {
      getBackupCodesRemaining().then(d => setBackupRemaining(d.remaining)).catch(() => {});
    }
  }, [user?.mfaEnabled]);

  const handleEnableEmail = async () => {
    setError(''); setSuccess('');
    try {
      await enableEmailMfa();
      setSuccess('Email OTP enabled as MFA method');
      refreshUser();
    } catch (e) { setError(e.response?.data?.message || 'Failed to enable email OTP'); }
  };

  const handleRegenCodes = async () => {
    setError(''); setSuccess('');
    try {
      const data = await regenerateBackupCodes();
      setBackupCodes(data.backupCodes);
      setBackupRemaining(data.backupCodes.length);
      setSuccess('New backup codes generated — save them securely');
    } catch (e) { setError(e.response?.data?.message || 'Failed to regenerate codes'); }
  };

  const handleLoadDevices = async () => {
    setLoadingDevices(true);
    try {
      const data = await getTrustedDevices();
      setDevices(data);
      setShowDevices(true);
    } catch { setDevices([]); setShowDevices(true); }
    finally { setLoadingDevices(false); }
  };

  const handleRevokeAll = async () => {
    if (!confirm('Revoke all trusted devices? Everyone will need to verify MFA on next login.')) return;
    setError(''); setSuccess('');
    try {
      await revokeAllDevices();
      setDevices([]);
      setSuccess('All trusted devices revoked');
    } catch (e) { setError(e.response?.data?.message || 'Failed to revoke devices'); }
  };

  return (
    <section className="card p-6">
      <h3 className="text-sm font-semibold text-text-primary mb-1">Multi-Factor Authentication</h3>
      <p className="text-xs text-text-muted mb-4">
        Add a second verification step to protect accounts. Supports authenticator apps (TOTP) and email OTP.
      </p>

      {/* Status */}
      <div className="flex items-center gap-3 mb-4 p-3 rounded-lg" style={{ background: 'var(--bg-2)' }}>
        <div className={`w-2 h-2 rounded-full ${user?.mfaEnabled ? 'bg-success' : 'bg-text-muted'}`} />
        <span className="text-sm font-medium text-text-primary">
          {user?.mfaEnabled ? 'MFA is enabled' : 'MFA is not enabled'}
        </span>
        {user?.mfaMethod && user.mfaMethod !== 'NONE' && (
          <span className="text-xs text-text-muted ml-auto">Method: {user.mfaMethod}</span>
        )}
      </div>

      {/* TOTP Setup */}
      {mfaSetup ? (
        <div className="mb-4 p-4 rounded-lg border border-border">
          <h4 className="text-xs font-semibold text-text-primary mb-3">Scan with your authenticator app</h4>
          <div className="bg-white p-4 rounded-lg inline-block mb-3">
            <img src={`https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(mfaSetup.qrCodeUrl)}`}
              alt="MFA QR Code" width={180} height={180} />
          </div>
          <p className="text-xs text-text-muted mb-3">Or enter manually: <code className="text-text-primary">{mfaSetup.secret}</code></p>
          <form onSubmit={onVerify} className="flex gap-2">
            <input type="text" placeholder="000000" value={mfaCode}
              onChange={e => setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              className="input-field flex-1 max-w-[120px] text-center tracking-widest" maxLength={6} />
            <button type="submit" className="btn-primary text-sm">Verify & Enable</button>
            <button type="button" onClick={onCancel} className="btn-secondary text-sm">Cancel</button>
          </form>
        </div>
      ) : !user?.mfaEnabled ? (
        <div className="flex gap-3 mb-4">
          <button onClick={onSetup} className="btn-primary text-sm">Setup Authenticator App (TOTP)</button>
          <button onClick={handleEnableEmail} className="btn-secondary text-sm">Use Email OTP Instead</button>
        </div>
      ) : null}

      {/* When MFA is enabled — show management options */}
      {user?.mfaEnabled && (
        <div className="space-y-4">
          {/* Backup Codes */}
          <div className="p-4 rounded-lg border border-border">
            <div className="flex items-center justify-between mb-2">
              <h4 className="text-xs font-semibold text-text-primary">Backup Codes</h4>
              {backupRemaining !== null && (
                <span className={`text-xs font-medium ${backupRemaining <= 2 ? 'text-danger' : 'text-text-muted'}`}>
                  {backupRemaining} remaining
                </span>
              )}
            </div>
            <p className="text-xs text-text-muted mb-3">
              Use backup codes to sign in if you lose access to your authenticator. Each code can only be used once.
            </p>
            {backupCodes ? (
              <div className="mb-3">
                <div className="grid grid-cols-2 gap-1 p-3 rounded bg-surface-el font-mono text-xs">
                  {backupCodes.map((code, i) => <div key={i}>{code}</div>)}
                </div>
                <p className="text-xs text-warning mt-2">Save these codes securely — they won't be shown again.</p>
              </div>
            ) : null}
            <button onClick={handleRegenCodes} className="btn-secondary text-xs">
              {backupCodes ? 'Regenerate New Codes' : 'View / Regenerate Codes'}
            </button>
          </div>

          {/* Trusted Devices */}
          <div className="p-4 rounded-lg border border-border">
            <div className="flex items-center justify-between mb-2">
              <h4 className="text-xs font-semibold text-text-primary">Trusted Devices</h4>
              <button onClick={handleLoadDevices} className="text-xs text-primary hover:underline" disabled={loadingDevices}>
                {loadingDevices ? 'Loading...' : showDevices ? 'Refresh' : 'View devices'}
              </button>
            </div>
            <p className="text-xs text-text-muted mb-3">
              Devices where you've chosen "Trust this device" won't require MFA for 90 days.
            </p>
            {showDevices && (
              <>
                {devices.length === 0 ? (
                  <p className="text-xs text-text-muted">No trusted devices.</p>
                ) : (
                  <div className="space-y-2 mb-3">
                    {devices.map(d => (
                      <div key={d.id} className="flex items-center justify-between text-xs p-2 rounded bg-surface-el">
                        <div>
                          <div className="text-text-primary">{d.userAgent?.substring(0, 60) || 'Unknown device'}</div>
                          <div className="text-text-muted">{d.ipAddress} · Last used: {d.lastUsedAt ? new Date(d.lastUsedAt).toLocaleDateString() : '—'}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                {devices.length > 0 && (
                  <button onClick={handleRevokeAll} className="btn-secondary text-xs text-danger">Revoke All Devices</button>
                )}
              </>
            )}
          </div>

          {/* Disable MFA */}
          <div className="pt-3 border-t border-border">
            <button onClick={onDisable} className="text-xs text-danger hover:underline">Disable MFA</button>
          </div>
        </div>
      )}
    </section>
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
