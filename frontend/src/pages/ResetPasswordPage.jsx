import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../api/client';

export default function ResetPasswordPage() {
  const { token } = useParams();
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (password.length < 12) { setError('Password must be at least 12 characters'); return; }
    if (password !== confirm) { setError('Passwords do not match'); return; }
    setSaving(true); setError('');
    try {
      await api.post('/auth/reset-password', { token, password });
      setDone(true);
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to reset password');
    } finally { setSaving(false); }
  };

  if (done) return (
    <div className="min-h-screen flex items-center justify-center bg-bg p-4">
      <div className="glass rounded-xl p-8 max-w-md w-full text-center">
        <h1 className="text-xl font-bold text-text-primary mb-2">Password reset</h1>
        <p className="text-text-muted text-sm mb-4">Your password has been updated. You can now log in.</p>
        <button onClick={() => navigate('/')} className="btn-primary text-sm">Go to Login</button>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg p-4">
      <div className="glass rounded-xl p-8 max-w-md w-full">
        <h1 className="text-xl font-bold text-text-primary mb-1">Reset your password</h1>
        <p className="text-text-muted text-sm mb-6">Enter a new password below.</p>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">New Password *</label>
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
            {saving ? 'Resetting...' : 'Reset Password'}
          </button>
        </form>
      </div>
    </div>
  );
}
