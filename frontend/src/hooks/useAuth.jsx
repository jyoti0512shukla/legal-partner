import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import apiClient from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  const fetchMe = useCallback(async () => {
    try {
      const res = await apiClient.get('/auth/me');
      setUser(res.data);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchMe(); }, [fetchMe]);

  const login = useCallback(async (email, password, mfaCode) => {
    const payload = { email, password };
    if (mfaCode) payload.mfaCode = mfaCode;
    const res = await apiClient.post('/auth/login', payload);
    // If MFA is required, token not yet set — return the response for caller to handle
    if (res.data.mfaRequired) return res.data;
    // Cookie is now set by server; fetch user info
    await fetchMe();
    return res.data;
  }, [fetchMe]);

  const logout = useCallback(async () => {
    try { await apiClient.post('/auth/logout'); } catch {}
    setUser(null);
    window.location.href = '/login';
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refetch: fetchMe }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
