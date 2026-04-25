import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { register } from '../api/auth';
import { Scale, AlertCircle } from 'lucide-react';

export default function SignUpPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const res = await register(email, password, displayName || undefined);
      if (res.token && res.user) {
        login(res);
        navigate('/');
      }
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Registration failed');
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg relative overflow-hidden">
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-primary/5 via-transparent to-transparent" />
      <div className="relative card w-full max-w-md mx-4">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 mb-4">
            <Scale className="w-7 h-7 text-gold" />
          </div>
          <h1 className="text-2xl font-bold text-text-primary">Create Account</h1>
          <p className="text-text-muted text-sm mt-1">Sign up for ContractIQ</p>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="email"
            placeholder="Email"
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(''); }}
            className="input-field w-full"
            required
          />
          <input
            type="text"
            placeholder="Display name (optional)"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className="input-field w-full"
          />
          <input
            type="password"
            placeholder="Password (min 12 chars, upper, lower, digit, special)"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(''); }}
            className="input-field w-full"
            required
            minLength={12}
          />
          {error && (
            <div className="flex items-center gap-2 text-danger text-sm">
              <AlertCircle className="w-4 h-4" /> {error}
            </div>
          )}
          <button type="submit" className="btn-primary w-full">Sign Up</button>
        </form>
        <p className="mt-4 text-center text-sm text-text-muted">
          Already have an account? <Link to="/" className="text-primary hover:underline">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
