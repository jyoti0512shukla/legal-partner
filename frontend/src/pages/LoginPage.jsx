import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { login } from '../api/auth';
import { Scale, AlertCircle } from 'lucide-react';

export default function LoginPage() {
  const { refetch } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState('');
  const [mfaToken, setMfaToken] = useState(null);
  const [mfaCode, setMfaCode] = useState('');
  const [accountLocked, setAccountLocked] = useState(null);
  const [passwordExpired, setPasswordExpired] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const res = await login(email, password, rememberMe);
      if (res.accountLocked) {
        setAccountLocked({ lockedUntil: res.lockedUntil });
        return;
      }
      if (res.passwordExpired && res.token) {
        setPasswordExpired({ token: res.token, email: res.user?.email });
        return;
      }
      if (res.mfaRequired && res.token) {
        setMfaToken(res.token);
        return;
      }
      if (res.token && res.user) {
        await refetch();
      }
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Invalid credentials');
    }
  };

  const handleMfaSubmit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const { validateMfa } = await import('../api/auth');
      const res = await validateMfa(mfaToken, mfaCode);
      if (res.token && res.user) {
        await refetch();
      }
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Invalid MFA code');
    }
  };

  if (accountLocked) {
    const until = accountLocked.lockedUntil ? new Date(accountLocked.lockedUntil).toLocaleTimeString() : '';
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg">
        <div className="card w-full max-w-md mx-4 text-center">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-danger/10 mb-4">
            <AlertCircle className="w-7 h-7 text-danger" />
          </div>
          <h1 className="text-xl font-bold text-text-primary mb-2">Account Locked</h1>
          <p className="text-text-muted text-sm mb-4">
            Too many failed login attempts. Try again after {until}.
          </p>
          <button onClick={() => setAccountLocked(null)} className="btn-secondary">
            Back to Login
          </button>
        </div>
      </div>
    );
  }

  if (passwordExpired) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg">
        <div className="card w-full max-w-md mx-4">
          <p className="text-center text-text-muted mb-4">
            Your password has expired. You must change it to continue.
          </p>
          <Link to="/change-password" state={{ token: passwordExpired.token, email: passwordExpired.email }} className="btn-primary w-full block text-center">
            Change Password
          </Link>
        </div>
      </div>
    );
  }

  if (mfaToken) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg">
        <div className="relative card w-full max-w-md mx-4">
          <div className="text-center mb-8">
            <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 mb-4">
              <Scale className="w-7 h-7 text-gold" />
            </div>
            <h1 className="text-2xl font-bold text-text-primary">Enter MFA Code</h1>
            <p className="text-text-muted text-sm mt-1">Enter the 6-digit code from your authenticator app</p>
          </div>
          <form onSubmit={handleMfaSubmit} className="space-y-4">
            <input
              type="text"
              placeholder="000000"
              value={mfaCode}
              onChange={(e) => { setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6)); setError(''); }}
              className="input-field w-full text-center text-2xl tracking-[0.5em]"
              maxLength={6}
            />
            {error && (
              <div className="flex items-center gap-2 text-danger text-sm">
                <AlertCircle className="w-4 h-4" /> {error}
              </div>
            )}
            <button type="submit" className="btn-primary w-full">Verify</button>
            <button type="button" onClick={() => { setMfaToken(null); setMfaCode(''); setError(''); }} className="btn-secondary w-full">
              Back
            </button>
          </form>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg relative overflow-hidden">
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-primary/5 via-transparent to-transparent" />
      <div className="relative card w-full max-w-md mx-4">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 mb-4">
            <Scale className="w-7 h-7 text-gold" />
          </div>
          <h1 className="text-2xl font-bold text-text-primary">ContractIQ</h1>
          <p className="text-text-muted text-sm mt-1">AI-Powered Contract Intelligence</p>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="email"
            placeholder="Email"
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(''); }}
            className="input-field w-full"
          />
          <input
            type="password"
            placeholder="Password"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(''); }}
            className="input-field w-full"
          />
          <label className="flex items-center gap-2 text-sm text-text-muted">
            <input type="checkbox" checked={rememberMe} onChange={(e) => setRememberMe(e.target.checked)} />
            Remember me
          </label>
          {error && (
            <div className="flex items-center gap-2 text-danger text-sm">
              <AlertCircle className="w-4 h-4" /> {error}
            </div>
          )}
          <button type="submit" className="btn-primary w-full">Sign In</button>
          <Link to="/forgot-password" className="block text-center text-sm text-text-muted hover:text-primary mt-2">Forgot password?</Link>
        </form>
        <p className="mt-4 text-center text-sm text-text-muted">
          Don&apos;t have an account? <Link to="/signup" className="text-primary hover:underline">Sign up</Link>
        </p>
      </div>
    </div>
  );
}
