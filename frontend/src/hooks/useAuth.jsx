import { createContext, useContext, useState, useCallback } from 'react';
import { setAuthHeader, clearAuthHeader } from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const saved = sessionStorage.getItem('lp_user');
    if (saved) {
      const parsed = JSON.parse(saved);
      setAuthHeader(parsed.username, parsed.password);
      return parsed;
    }
    return null;
  });

  const login = useCallback((username, password, role) => {
    setAuthHeader(username, password);
    const userData = { username, password, role: `ROLE_${role.toUpperCase()}` };
    sessionStorage.setItem('lp_user', JSON.stringify(userData));
    setUser(userData);
  }, []);

  const logout = useCallback(() => {
    clearAuthHeader();
    sessionStorage.removeItem('lp_user');
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be inside AuthProvider');
  return ctx;
}
