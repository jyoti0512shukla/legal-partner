import { useState } from 'react';
import { useAuth } from '../hooks/useAuth';
import { Scale, AlertCircle } from 'lucide-react';

const USERS = [
  { username: 'partner', password: 'partner123', role: 'PARTNER', label: 'Partner' },
  { username: 'associate', password: 'associate123', role: 'ASSOCIATE', label: 'Associate' },
  { username: 'admin', password: 'admin123', role: 'ADMIN', label: 'Admin' },
];

export default function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    const user = USERS.find(u => u.username === username && u.password === password);
    if (user) {
      login(user.username, user.password, user.role);
    } else {
      setError('Invalid credentials');
    }
  };

  const quickLogin = (u) => login(u.username, u.password, u.role);

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg relative overflow-hidden">
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-primary/5 via-transparent to-transparent" />

      <div className="relative card w-full max-w-md mx-4">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 mb-4">
            <Scale className="w-7 h-7 text-gold" />
          </div>
          <h1 className="text-2xl font-bold text-text-primary">Legal Partner</h1>
          <p className="text-text-muted text-sm mt-1">Private Contract Intelligence</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="text" placeholder="Username" value={username}
            onChange={e => { setUsername(e.target.value); setError(''); }}
            className="input-field w-full"
          />
          <input
            type="password" placeholder="Password" value={password}
            onChange={e => { setPassword(e.target.value); setError(''); }}
            className="input-field w-full"
          />
          {error && (
            <div className="flex items-center gap-2 text-danger text-sm">
              <AlertCircle className="w-4 h-4" /> {error}
            </div>
          )}
          <button type="submit" className="btn-primary w-full">Sign In</button>
        </form>

        <div className="mt-6 pt-6 border-t border-border">
          <p className="text-xs text-text-muted mb-3 text-center">Quick demo access</p>
          <div className="flex gap-2">
            {USERS.map(u => (
              <button key={u.username} onClick={() => quickLogin(u)}
                className="btn-secondary flex-1 text-xs py-2">
                {u.label}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
