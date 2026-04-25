import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Loader2, AlertCircle } from 'lucide-react';
import api from '../api/client';
import { login as apiLogin } from '../api/auth';
import { useAuth } from '../hooks/useAuth';

export default function AcceptInvitePage() {
  const { token } = useParams();
  const navigate = useNavigate();
  const { refreshUser } = useAuth();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(true);
  const [invalid, setInvalid] = useState(null);

  // Validate token on load
  useEffect(() => {
    api.get(`/auth/validate-token?token=${token}&type=INVITE`)
      .then(r => {
        setEmail(r.data?.email || '');
        setValidating(false);
      })
      .catch(err => {
        setInvalid(err.response?.data?.message || 'This invite link is invalid or has expired.');
        setValidating(false);
      });
  }, [token]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (password.length < 12) { setError('Password must be at least 12 characters'); return; }
    if (password !== confirm) { setError('Passwords do not match'); return; }
    setSaving(true); setError('');
    try {
      await api.post('/auth/accept-invite', { token, password, displayName });
      // Auto-login with the new credentials
      if (email) {
        try {
          const result = await apiLogin(email, password);
          // If MFA required, redirect to login page
          if (result?.mfaRequired) {
            window.location.href = '/';
            return;
          }
          // Force full page reload to re-initialize auth context
          window.location.href = '/';
          return;
        } catch {
          // Login failed — redirect to login page
          window.location.href = '/';
          return;
        }
      }
      window.location.href = '/';
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to accept invite. The link may have already been used.');
    } finally { setSaving(false); }
  };

  if (validating) return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <Loader2 className="w-6 h-6 animate-spin text-text-muted" />
    </div>
  );

  if (invalid) return (
    <div className="min-h-screen flex items-center justify-center bg-bg p-4">
      <div className="glass rounded-xl p-8 max-w-md w-full text-center">
        <AlertCircle className="w-10 h-10 text-danger mx-auto mb-3" />
        <h1 className="text-xl font-bold text-text-primary mb-2">Invalid Invite</h1>
        <p className="text-text-muted text-sm mb-4">{invalid}</p>
        <button onClick={() => navigate('/')} className="btn-secondary text-sm">Go to Login</button>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg p-4">
      <div className="glass rounded-xl p-8 max-w-md w-full">
        <h1 className="text-xl font-bold text-text-primary mb-1">Welcome to ContractIQ</h1>
        <p className="text-text-muted text-sm mb-6">
          Set your password to activate your account{email ? ` for ${email}` : ''}.
        </p>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">Display Name</label>
            <input value={displayName} onChange={e => setDisplayName(e.target.value)}
              placeholder="e.g. Sarah Johnson" className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Password *</label>
            <input type="password" required value={password} onChange={e => setPassword(e.target.value)}
              placeholder="Minimum 12 characters" className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Confirm Password *</label>
            <input type="password" required value={confirm} onChange={e => setConfirm(e.target.value)}
              className="input-field w-full text-sm" />
          </div>
          {error && <p className="text-danger text-sm">{error}</p>}
          <button type="submit" disabled={saving} className="btn-primary w-full text-sm">
            {saving ? 'Activating...' : 'Set Password & Activate'}
          </button>
        </form>
      </div>
    </div>
  );
}
