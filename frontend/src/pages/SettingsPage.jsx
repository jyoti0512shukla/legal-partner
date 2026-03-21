import { useState, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { setupMfa, verifyMfa, disableMfa } from '../api/auth';
import { AlertCircle } from 'lucide-react';
import IntegrationsTab from '../components/IntegrationsTab';

const TABS = [
  { id: 'profile', label: 'Profile' },
  { id: 'integrations', label: 'Integrations' },
];

export default function SettingsPage() {
  const { user, refreshUser } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [activeTab, setActiveTab] = useState(searchParams.get('tab') || 'profile');
  const [mfaSetup, setMfaSetup] = useState(null);
  const [mfaCode, setMfaCode] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Handle OAuth callback redirect (e.g., /settings?tab=integrations&connected=DOCUSIGN)
  useEffect(() => {
    const tab = searchParams.get('tab');
    if (tab) setActiveTab(tab);
    const connected = searchParams.get('connected');
    if (connected) {
      setSuccess(`${connected} connected successfully`);
      // Clean URL
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
    setError('');
    setSuccess('');
    try {
      const res = await setupMfa();
      setMfaSetup(res);
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Failed to setup MFA');
    }
  };

  const handleVerifyMfa = async (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    try {
      await verifyMfa(mfaCode);
      setSuccess('MFA enabled successfully');
      setMfaSetup(null);
      setMfaCode('');
      refreshUser();
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Invalid code');
    }
  };

  const handleDisableMfa = async () => {
    setError('');
    setSuccess('');
    try {
      await disableMfa();
      setSuccess('MFA disabled');
      refreshUser();
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Failed to disable MFA');
    }
  };

  return (
    <div className="p-6 max-w-3xl">
      <h1 className="text-2xl font-bold text-text-primary mb-6">Settings</h1>

      {/* Tab Navigation */}
      <div className="flex gap-6 border-b border-border mb-6">
        {TABS.map(tab => (
          <button
            key={tab.id}
            onClick={() => handleTabChange(tab.id)}
            className={`pb-3 px-1 text-sm font-medium border-b-2 transition-colors ${
              activeTab === tab.id
                ? 'border-primary text-primary'
                : 'border-transparent text-text-muted hover:text-text-primary'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Global messages */}
      {error && (
        <div className="flex items-center gap-2 text-danger text-sm mb-4">
          <AlertCircle className="w-4 h-4" /> {error}
        </div>
      )}
      {success && (
        <div className="text-success text-sm mb-4">{success}</div>
      )}

      {/* Profile Tab */}
      {activeTab === 'profile' && (
        <div>
          <section className="card p-6 mb-6">
            <h2 className="text-lg font-semibold text-text-primary mb-4">Multi-Factor Authentication</h2>

            {mfaSetup ? (
              <div>
                <p className="text-text-muted text-sm mb-4">
                  Scan this QR code with your authenticator app (Google Authenticator, Authy, etc.), then enter the 6-digit code below.
                </p>
                <div className="bg-white p-4 rounded-lg inline-block mb-4">
                  <img
                    src={`https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(mfaSetup.qrCodeUrl)}`}
                    alt="MFA QR Code"
                    width={200}
                    height={200}
                  />
                </div>
                <p className="text-xs text-text-muted mb-2">Or enter this secret manually: {mfaSetup.secret}</p>
                <form onSubmit={handleVerifyMfa} className="flex gap-2">
                  <input
                    type="text"
                    placeholder="000000"
                    value={mfaCode}
                    onChange={(e) => setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                    className="input-field flex-1 max-w-[120px] text-center tracking-widest"
                    maxLength={6}
                  />
                  <button type="submit" className="btn-primary">Verify & Enable</button>
                  <button type="button" onClick={() => { setMfaSetup(null); setMfaCode(''); setError(''); }} className="btn-secondary">
                    Cancel
                  </button>
                </form>
              </div>
            ) : (
              <div className="flex gap-4">
                <button onClick={handleEnableMfa} className="btn-primary">
                  Enable MFA
                </button>
                {user?.mfaEnabled && (
                  <button onClick={handleDisableMfa} className="btn-secondary text-danger">
                    Disable MFA
                  </button>
                )}
              </div>
            )}
          </section>

          <section className="card p-6">
            <h2 className="text-lg font-semibold text-text-primary mb-4">Change Password</h2>
            <Link to="/change-password" className="btn-secondary inline-block">
              Change Password
            </Link>
          </section>
        </div>
      )}

      {/* Integrations Tab */}
      {activeTab === 'integrations' && (
        <IntegrationsTab />
      )}
    </div>
  );
}
