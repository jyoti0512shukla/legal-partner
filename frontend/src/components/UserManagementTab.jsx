import { useState, useEffect } from 'react';
import { Loader2, UserPlus, Ban, CheckCircle2, Mail, Search, X, Trash2, Key, Shield, Users } from 'lucide-react';
import api from '../api/client';

const ROLES = ['ADMIN', 'PARTNER', 'ASSOCIATE'];
const ROLE_COLORS = { ADMIN: 'bg-danger/10 text-danger', PARTNER: 'bg-primary/10 text-primary', ASSOCIATE: 'bg-warning/10 text-warning' };
const STATUS_COLORS = { ACTIVE: 'bg-success/10 text-success', INVITED: 'bg-warning/10 text-warning', DISABLED: 'bg-danger/10 text-danger' };

function friendlyError(e) {
  const raw = e?.response?.data?.message || e?.response?.data?.error || e?.message || 'Something went wrong';
  // Strip HTTP status codes and Spring error prefixes
  return raw
    .replace(/^\d{3}\s+[A-Z_]+\s+"?/, '')
    .replace(/"$/, '')
    .replace(/^[A-Z_]+\s+"/, '');
}

export default function UserManagementTab() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newUser, setNewUser] = useState({ email: '', displayName: '', role: 'ASSOCIATE', sendInvite: true });
  const [creating, setCreating] = useState(false);
  const [selectedUser, setSelectedUser] = useState(null);

  const fetchUsers = () => {
    setLoading(true);
    api.get('/admin/users').then(r => setUsers(r.data || [])).catch(() => {}).finally(() => setLoading(false));
  };
  useEffect(fetchUsers, []);

  const filtered = users.filter(u => {
    if (!search.trim()) return true;
    const q = search.toLowerCase();
    return (u.email || '').toLowerCase().includes(q) || (u.displayName || '').toLowerCase().includes(q) || (u.role || '').toLowerCase().includes(q);
  });

  const handleAction = async (fn) => { try { await fn(); fetchUsers(); } catch (e) { alert(friendlyError(e)); } };

  const handleCreateUser = async () => {
    if (!newUser.email.trim()) return;
    setCreating(true);
    try {
      const res = await api.post('/admin/users', newUser);
      if (res.data?.warning) alert('Warning: ' + res.data.warning);
      setNewUser({ email: '', displayName: '', role: 'ASSOCIATE', sendInvite: true });
      setShowCreate(false); fetchUsers();
    } catch (e) { alert(e.response?.data?.message || 'Failed to create user.'); }
    finally { setCreating(false); }
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-8"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-text-primary">Users</h2>
          <p className="text-sm text-text-muted">{users.length} users</p>
        </div>
        <button onClick={() => setShowCreate(!showCreate)} className="btn-primary text-sm flex items-center gap-1.5">
          <UserPlus className="w-4 h-4" /> Create User
        </button>
      </div>

      {showCreate && (
        <div className="card p-4 border-primary/30">
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
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={newUser.sendInvite} onChange={e => setNewUser({ ...newUser, sendInvite: e.target.checked })} className="w-4 h-4" />
                Send invite email
              </label>
            </div>
          </div>
          <div className="flex gap-2">
            <button onClick={handleCreateUser} disabled={creating || !newUser.email.trim()} className="btn-primary text-sm">
              {creating ? 'Creating...' : 'Create & Invite'}
            </button>
            <button onClick={() => setShowCreate(false)} className="btn-secondary text-sm">Cancel</button>
          </div>
        </div>
      )}

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-text-muted pointer-events-none" />
        <input type="text" placeholder="Search by email, name, or role..."
          value={search} onChange={e => setSearch(e.target.value)} className="input-field w-full pl-10 pr-9 text-sm" />
        {search && <button onClick={() => setSearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-primary"><X className="w-4 h-4" /></button>}
      </div>

      {/* Table */}
      {filtered.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">{search ? `No users matching "${search}"` : 'No users yet.'}</p>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-xs text-text-muted">
                <th className="text-left px-4 py-3 font-medium">User</th>
                <th className="text-left px-4 py-3 font-medium">Role</th>
                <th className="text-left px-4 py-3 font-medium">Status</th>
                <th className="text-left px-4 py-3 font-medium">Last Login</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {filtered.map(u => (
                <tr key={u.id} onClick={() => setSelectedUser(u)}
                  className={`cursor-pointer transition-colors ${selectedUser?.id === u.id ? 'bg-primary/5' : 'hover:bg-surface-el/50'}`}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2.5">
                      <div className="w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-primary text-xs font-medium shrink-0">
                        {(u.displayName || u.email || '?')[0].toUpperCase()}
                      </div>
                      <div>
                        <div className="font-medium text-text-primary">{u.displayName || u.email}</div>
                        <div className="text-[10px] text-text-muted">{u.email}</div>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${ROLE_COLORS[u.role] || ''}`}>{u.role}</span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${STATUS_COLORS[u.accountStatus] || ''}`}>{u.accountStatus}</span>
                    {u.mfaEnabled && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-primary/10 text-primary ml-1">MFA</span>}
                  </td>
                  <td className="px-4 py-3 text-text-muted text-xs">
                    {u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleDateString() : 'Never'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedUser && (
        <UserSidePanel
          user={selectedUser}
          onClose={() => setSelectedUser(null)}
          onAction={() => { fetchUsers(); setSelectedUser(null); }}
          onRefresh={(userId) => {
            api.get('/admin/users').then(r => {
              const updated = (r.data || []).find(u => u.id === userId);
              if (updated) setSelectedUser(updated);
              setUsers(r.data || []);
            });
          }}
        />
      )}
    </div>
  );
}

function UserSidePanel({ user: u, onClose, onAction, onRefresh }) {
  const [teams, setTeams] = useState([]);
  const [loadingTeams, setLoadingTeams] = useState(true);

  useEffect(() => {
    api.get('/teams').then(r => {
      const allTeams = r.data || [];
      // Check which teams this user belongs to
      Promise.all(allTeams.map(t =>
        api.get(`/teams/${t.id}/members`).then(res => ({
          ...t,
          isMember: (res.data || []).some(m => m.userId === u.id),
        })).catch(() => ({ ...t, isMember: false }))
      )).then(setTeams).finally(() => setLoadingTeams(false));
    }).catch(() => setLoadingTeams(false));
  }, [u.id]);

  const [actionMsg, setActionMsg] = useState(null);

  const action = async (fn, successMsg) => {
    try {
      await fn();
      onRefresh(u.id);
      if (successMsg) { setActionMsg(successMsg); setTimeout(() => setActionMsg(null), 3000); }
    } catch (e) { alert(friendlyError(e)); }
  };

  return (
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/40" />
      <div className="relative w-full max-w-md bg-surface border-l border-border shadow-xl overflow-y-auto"
        onClick={e => e.stopPropagation()}>
        <div className="p-6">
          {/* Header */}
          <div className="flex items-start justify-between mb-6">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-full bg-primary/10 flex items-center justify-center text-primary text-lg font-semibold">
                {(u.displayName || u.email || '?')[0].toUpperCase()}
              </div>
              <div>
                <h2 className="text-lg font-semibold text-text-primary">{u.displayName || u.email}</h2>
                <p className="text-sm text-text-muted">{u.email}</p>
              </div>
            </div>
            <button onClick={onClose} className="text-text-muted hover:text-text-primary p-1"><X className="w-5 h-5" /></button>
          </div>

          {/* Status */}
          <div className="flex items-center gap-2 mb-6">
            <span className={`text-xs px-2.5 py-1 rounded-full font-medium ${ROLE_COLORS[u.role] || ''}`}>{u.role}</span>
            <span className={`text-xs px-2.5 py-1 rounded-full font-medium ${STATUS_COLORS[u.accountStatus] || ''}`}>{u.accountStatus}</span>
            {u.mfaEnabled && <span className="text-xs px-2.5 py-1 rounded-full bg-primary/10 text-primary flex items-center gap-1"><Shield className="w-3 h-3" /> MFA</span>}
          </div>

          {/* Success message */}
          {actionMsg && (
            <div className="bg-success/10 text-success text-sm px-3 py-2 rounded-lg mb-4">{actionMsg}</div>
          )}

          {/* Details */}
          <div className="space-y-2 mb-6 text-sm">
            <div className="flex justify-between"><span className="text-text-muted">Joined</span><span>{new Date(u.createdAt).toLocaleDateString()}</span></div>
            <div className="flex justify-between"><span className="text-text-muted">Last login</span><span>{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : 'Never'}</span></div>
          </div>

          <hr className="border-border mb-6" />

          {/* Role */}
          <div className="mb-6">
            <label className="text-xs text-text-muted mb-1.5 block">Role</label>
            <select value={u.role} onChange={e => action(() => api.patch(`/admin/users/${u.id}/role?role=${e.target.value}`))}
              className="input-field w-full text-sm">
              {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>

          {/* Teams */}
          <div className="mb-6">
            <div className="flex items-center gap-1.5 mb-2">
              <Users className="w-4 h-4 text-text-muted" />
              <span className="text-xs font-medium text-text-muted">Teams</span>
            </div>
            {loadingTeams ? (
              <div className="text-xs text-text-muted flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> Loading...</div>
            ) : teams.length === 0 ? (
              <p className="text-xs text-text-muted">No teams created yet.</p>
            ) : (
              <div className="space-y-1.5">
                {teams.map(t => (
                  <div key={t.id} className="flex items-center justify-between px-2 py-1.5 rounded-lg hover:bg-surface-el text-sm">
                    <span className="text-text-primary">{t.name}</span>
                    {t.isMember ? (
                      <span className="text-[10px] px-2 py-0.5 rounded-full bg-success/10 text-success">Member</span>
                    ) : (
                      <span className="text-[10px] text-text-muted">—</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          <hr className="border-border mb-4" />

          {/* Actions */}
          <div className="space-y-2">
            {(u.accountStatus === 'INVITED' || !u.enabled) && (
              <button onClick={() => action(() => api.post(`/admin/users/${u.id}/resend-invite`), 'Invite email sent successfully')}
                className="btn-secondary w-full text-sm flex items-center justify-center gap-2">
                <Mail className="w-4 h-4" /> Resend Invite Email
              </button>
            )}
            {u.enabled && u.accountStatus === 'ACTIVE' && (
              <button onClick={() => action(() => api.post(`/admin/users/${u.id}/reset-password`), 'Password reset email sent')}
                className="btn-secondary w-full text-sm flex items-center justify-center gap-2">
                <Key className="w-4 h-4" /> Send Password Reset
              </button>
            )}
            {u.enabled ? (
              <button onClick={() => action(() => api.patch(`/admin/users/${u.id}/enable?enabled=false`))}
                className="btn-secondary w-full text-sm text-warning flex items-center justify-center gap-2">
                <Ban className="w-4 h-4" /> Disable Account
              </button>
            ) : (
              <button onClick={() => action(() => api.patch(`/admin/users/${u.id}/enable?enabled=true`))}
                className="btn-secondary w-full text-sm text-success flex items-center justify-center gap-2">
                <CheckCircle2 className="w-4 h-4" /> Enable Account
              </button>
            )}
          </div>

          <div className="pt-4 mt-4 border-t border-border">
            <p className="text-xs text-text-muted mb-2">Danger Zone</p>
            <button onClick={() => { if (confirm(`Delete ${u.email}?`)) action(() => api.delete(`/admin/users/${u.id}`)).then(onAction); }}
              className="btn-secondary w-full text-sm text-danger flex items-center justify-center gap-2 border-danger/30 hover:bg-danger/10">
              <Trash2 className="w-4 h-4" /> Delete User
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
