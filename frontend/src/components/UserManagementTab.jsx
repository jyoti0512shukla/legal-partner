import { useState, useEffect } from 'react';
import { Loader2, Save, UserPlus, Ban, CheckCircle2, Mail, Shield } from 'lucide-react';
import api from '../api/client';

const ROLES = ['ADMIN', 'PARTNER', 'ASSOCIATE'];
const ROLE_COLORS = {
  ADMIN: 'bg-danger/10 text-danger',
  PARTNER: 'bg-primary/10 text-primary',
  ASSOCIATE: 'bg-warning/10 text-warning',
};

export default function UserManagementTab() {
  const [users, setUsers] = useState([]);
  const [config, setConfig] = useState(null);
  const [loading, setLoading] = useState(true);
  const [savingConfig, setSavingConfig] = useState(false);
  const [configSuccess, setConfigSuccess] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [newUser, setNewUser] = useState({ email: '', displayName: '', role: 'ASSOCIATE', sendInvite: true });
  const [creating, setCreating] = useState(false);

  const fetchData = () => {
    Promise.all([
      api.get('/admin/users'),
      api.get('/admin/users/config'),
    ]).then(([usersRes, configRes]) => {
      setUsers(usersRes.data || []);
      setConfig(configRes.data);
    }).catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(fetchData, []);

  const handleRoleChange = async (userId, newRole) => {
    try {
      await api.patch(`/admin/users/${userId}/role?role=${newRole}`);
      fetchData();
    } catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleToggleEnabled = async (userId, enabled) => {
    try {
      await api.patch(`/admin/users/${userId}/enable?enabled=${enabled}`);
      fetchData();
    } catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleResendInvite = async (userId) => {
    try {
      await api.post(`/admin/users/${userId}/resend-invite`);
      alert('Invite sent');
    } catch (e) { alert(e.response?.data?.message || 'Failed to resend invite'); }
  };

  const handleCreateUser = async () => {
    if (!newUser.email.trim()) return;
    setCreating(true);
    try {
      await api.post('/admin/users', newUser);
      setNewUser({ email: '', displayName: '', role: 'ASSOCIATE', sendInvite: true });
      setShowCreate(false);
      fetchData();
    } catch (e) { alert(e.response?.data?.message || 'Failed to create user'); }
    finally { setCreating(false); }
  };

  const handleDeleteUser = async (userId, email) => {
    if (!confirm(`Delete user ${email}? This cannot be undone.`)) return;
    try {
      await api.delete(`/admin/users/${userId}`);
      fetchData();
    } catch (e) { alert(e.response?.data?.message || 'Failed to delete user'); }
  };

  const handleSaveConfig = async () => {
    setSavingConfig(true); setConfigSuccess(false);
    try {
      const res = await api.put('/admin/users/config', config);
      setConfig(res.data);
      setConfigSuccess(true);
      setTimeout(() => setConfigSuccess(false), 3000);
    } catch (e) { alert('Failed to save'); }
    finally { setSavingConfig(false); }
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-8"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  return (
    <div className="space-y-6">
      {/* Users list */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-lg font-semibold text-text-primary">Users</h2>
            <p className="text-sm text-text-muted">{users.length} users</p>
          </div>
          <button onClick={() => setShowCreate(!showCreate)} className="btn-primary text-sm flex items-center gap-1.5">
            <UserPlus className="w-4 h-4" /> Create User
          </button>
        </div>

        {/* Create user form */}
        {showCreate && (
          <div className="card p-4 mb-4 border-primary/30">
            <p className="text-xs font-medium text-text-muted mb-3">New User</p>
            <div className="grid grid-cols-2 gap-3 mb-3">
              <div>
                <label className="text-xs text-text-muted mb-1 block">Email *</label>
                <input type="email" value={newUser.email} onChange={e => setNewUser({ ...newUser, email: e.target.value })}
                  placeholder="user@firm.com" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Display Name</label>
                <input value={newUser.displayName} onChange={e => setNewUser({ ...newUser, displayName: e.target.value })}
                  placeholder="Jane Smith" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Role</label>
                <select value={newUser.role} onChange={e => setNewUser({ ...newUser, role: e.target.value })} className="input-field w-full text-sm">
                  {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                </select>
              </div>
              <div className="flex items-end pb-1">
                <label className="flex items-center gap-2 text-sm text-text-primary">
                  <input type="checkbox" checked={newUser.sendInvite} onChange={e => setNewUser({ ...newUser, sendInvite: e.target.checked })} className="w-4 h-4" />
                  Send invite email
                </label>
              </div>
            </div>
            <div className="flex gap-2">
              <button onClick={handleCreateUser} disabled={creating || !newUser.email.trim()} className="btn-primary text-sm flex items-center gap-1.5">
                {creating ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserPlus className="w-4 h-4" />}
                {creating ? 'Creating...' : 'Create & Invite'}
              </button>
              <button onClick={() => setShowCreate(false)} className="btn-secondary text-sm">Cancel</button>
            </div>
          </div>
        )}

        <div className="space-y-2">
          {users.map(u => (
            <div key={u.id} className="card p-3 flex items-center justify-between group">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-primary text-sm font-medium">
                  {(u.displayName || u.email || '?')[0].toUpperCase()}
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-text-primary">{u.displayName || u.email}</span>
                    {u.accountStatus !== 'ACTIVE' && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-warning/10 text-warning">{u.accountStatus}</span>
                    )}
                    {!u.enabled && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-danger/10 text-danger">DISABLED</span>
                    )}
                    {u.mfaEnabled && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-success/10 text-success">MFA</span>
                    )}
                  </div>
                  <div className="text-[10px] text-text-muted">
                    {u.email} · Joined {new Date(u.createdAt).toLocaleDateString()}
                    {u.lastLoginAt && <span> · Last login {new Date(u.lastLoginAt).toLocaleDateString()}</span>}
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <select value={u.role} onChange={e => handleRoleChange(u.id, e.target.value)}
                  className="text-xs bg-surface-el border border-border rounded px-2 py-1">
                  {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                </select>

                <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${ROLE_COLORS[u.role] || ''}`}>
                  {u.role}
                </span>

                {u.accountStatus === 'INVITED' || !u.enabled ? (
                  <div className="flex gap-1">
                    {u.accountStatus === 'INVITED' && (
                      <button onClick={() => handleResendInvite(u.id)}
                        className="text-primary hover:bg-primary/10 p-1.5 rounded" title="Resend invite">
                        <Mail className="w-3.5 h-3.5" />
                      </button>
                    )}
                    <button onClick={() => handleToggleEnabled(u.id, true)}
                      className="text-success hover:bg-success/10 p-1.5 rounded" title="Enable">
                      <CheckCircle2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ) : (
                  <button onClick={() => handleToggleEnabled(u.id, false)}
                    className="text-text-muted hover:text-danger hover:bg-danger/10 p-1.5 rounded" title="Disable">
                    <Ban className="w-3.5 h-3.5" />
                  </button>
                )}
                <button onClick={() => handleDeleteUser(u.id, u.email)}
                  className="text-text-muted hover:text-danger hover:bg-danger/10 p-1 rounded opacity-0 group-hover:opacity-100 transition-opacity" title="Delete user">
                  <span className="text-[10px]">×</span>
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Auth config */}
      {config && (
        <div>
          <h2 className="text-lg font-semibold text-text-primary mb-1">Security Settings</h2>
          <p className="text-sm text-text-muted mb-4">Configure invite and password reset limits.</p>

          <div className="card p-5">
            <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
              <div>
                <label className="text-xs text-text-muted mb-1 block">Invite expiry (hours)</label>
                <input type="number" min="1" value={config.inviteExpiryHours}
                  onChange={e => setConfig({ ...config, inviteExpiryHours: +e.target.value })}
                  className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Invite resend cooldown (min)</label>
                <input type="number" min="1" value={config.inviteResendCooldownMin}
                  onChange={e => setConfig({ ...config, inviteResendCooldownMin: +e.target.value })}
                  className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Password reset expiry (hours)</label>
                <input type="number" min="1" value={config.passwordResetExpiryHours}
                  onChange={e => setConfig({ ...config, passwordResetExpiryHours: +e.target.value })}
                  className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Max resets per hour</label>
                <input type="number" min="1" value={config.maxPasswordResetsPerHour}
                  onChange={e => setConfig({ ...config, maxPasswordResetsPerHour: +e.target.value })}
                  className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Max failed logins</label>
                <input type="number" min="1" value={config.maxFailedLogins}
                  onChange={e => setConfig({ ...config, maxFailedLogins: +e.target.value })}
                  className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Lockout duration (min)</label>
                <input type="number" min="1" value={config.lockoutDurationMinutes}
                  onChange={e => setConfig({ ...config, lockoutDurationMinutes: +e.target.value })}
                  className="input-field w-full text-sm" />
              </div>
            </div>

            <button onClick={handleSaveConfig} disabled={savingConfig} className="btn-primary text-sm mt-4 flex items-center gap-2">
              {savingConfig ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              {savingConfig ? 'Saving...' : configSuccess ? 'Saved!' : 'Save Settings'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
