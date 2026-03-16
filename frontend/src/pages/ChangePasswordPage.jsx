import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { changePassword, login as apiLogin } from '../api/auth';
import { Scale, AlertCircle } from 'lucide-react';

export default function ChangePasswordPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const tokenFromState = location.state?.token;
  const emailFromState = location.state?.email;

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (newPassword !== confirmPassword) {
      setError('New passwords do not match');
      return;
    }
    if (newPassword.length < 12) {
      setError('Password must be at least 12 characters');
      return;
    }
    try {
      await changePassword(currentPassword, newPassword, tokenFromState || null);
      if (tokenFromState && emailFromState) {
        const res = await apiLogin(emailFromState, newPassword, false);
        if (res.token && res.user) {
          login(res);
        }
      }
      navigate('/');
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Failed to change password');
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="card w-full max-w-md mx-4">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 mb-4">
            <Scale className="w-7 h-7 text-gold" />
          </div>
          <h1 className="text-2xl font-bold text-text-primary">Change Password</h1>
          <p className="text-text-muted text-sm mt-1">
            {tokenFromState ? 'Your password has expired. Enter a new password.' : 'Enter your current password and choose a new one.'}
          </p>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="password"
            placeholder="Current password"
            value={currentPassword}
            onChange={(e) => { setCurrentPassword(e.target.value); setError(''); }}
            className="input-field w-full"
            required
          />
          <input
            type="password"
            placeholder="New password (min 12 chars, upper, lower, digit, special)"
            value={newPassword}
            onChange={(e) => { setNewPassword(e.target.value); setError(''); }}
            className="input-field w-full"
            required
            minLength={12}
          />
          <input
            type="password"
            placeholder="Confirm new password"
            value={confirmPassword}
            onChange={(e) => { setConfirmPassword(e.target.value); setError(''); }}
            className="input-field w-full"
            required
          />
          {error && (
            <div className="flex items-center gap-2 text-danger text-sm">
              <AlertCircle className="w-4 h-4" /> {error}
            </div>
          )}
          <button type="submit" className="btn-primary w-full">Change Password</button>
        </form>
      </div>
    </div>
  );
}
