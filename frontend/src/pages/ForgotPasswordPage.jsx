import { useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api/client';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true); setError('');
    try {
      await api.post('/auth/forgot-password', { email });
      setSent(true);
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to send reset email');
    } finally { setLoading(false); }
  };

  if (sent) return (
    <div className="min-h-screen flex items-center justify-center bg-bg p-4">
      <div className="glass rounded-xl p-8 max-w-md w-full text-center">
        <h1 className="text-xl font-bold text-text-primary mb-2">Check your email</h1>
        <p className="text-text-muted text-sm mb-4">If an account exists for {email}, we've sent a password reset link.</p>
        <Link to="/" className="text-primary text-sm hover:underline">Back to Login</Link>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg p-4">
      <div className="glass rounded-xl p-8 max-w-md w-full">
        <h1 className="text-xl font-bold text-text-primary mb-1">Forgot your password?</h1>
        <p className="text-text-muted text-sm mb-6">Enter your email and we'll send you a reset link.</p>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">Email</label>
            <input type="email" required value={email} onChange={e => setEmail(e.target.value)}
              placeholder="you@firm.com" className="input-field w-full text-sm" />
          </div>
          {error && <p className="text-danger text-sm">{error}</p>}
          <button type="submit" disabled={loading} className="btn-primary w-full text-sm">
            {loading ? 'Sending...' : 'Send Reset Link'}
          </button>
          <Link to="/" className="block text-center text-text-muted text-sm hover:text-primary">Back to Login</Link>
        </form>
      </div>
    </div>
  );
}
