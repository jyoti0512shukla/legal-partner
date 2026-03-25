import { useState, useEffect } from 'react';
import { Loader2, UserPlus, Ban, CheckCircle2, Mail, Search, X, Trash2, ChevronDown } from 'lucide-react';
import api from '../api/client';

const ROLES = ['ADMIN', 'PARTNER', 'ASSOCIATE'];
const ROLE_COLORS = {
  ADMIN: 'bg-danger/10 text-danger',
  PARTNER: 'bg-primary/10 text-primary',
  ASSOCIATE: 'bg-warning/10 text-warning',
};

export default function UserManagementTab() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newUser, setNewUser] = useState({ email: '', displayName: '', role: 'ASSOCIATE', sendInvite: true });
  const [creating, setCreating] = useState(false);
  const [expanded, setExpanded] = useState(null);

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

  const handleRoleChange = async (userId, newRole) => {
    try { await api.patch(`/admin/users/${userId}/role?role=${newRole}`); fetchUsers(); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleToggleEnabled = async (userId, enabled) => {
    try { await api.patch(`/admin/users/${userId}/enable?enabled=${enabled}`); fetchUsers(); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleResendInvite = async (userId) => {
    try { await api.post(`/admin/users/${userId}/resend-invite`); alert('Invite sent'); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleDeleteUser = async (userId, email) => {
    if (!confirm(`Delete user ${email}? This cannot be undone.`)) return;
    try { await api.delete(`/admin/users/${userId}`); fetchUsers(); setExpanded(null); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleCreateUser = async () => {
    if (!newUser.email.trim()) return;
    setCreating(true);
    try {
      await api.post('/admin/users', newUser);
      setNewUser({ email: '', displayName: '', role: 'ASSOCIATE', sendInvite: true });
      setShowCreate(false); fetchUsers();
    } catch (e) { alert(e.response?.data?.message || 'Failed'); }
    finally { setCreating(false); }
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-8"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-text-primary">Users</h2>
          <p className="text-sm text-text-muted">{users.length} users</p>
        </div>
        <button onClick={() => setShowCreate(!showCreate)} className="btn-primary text-sm flex items-center gap-1.5">
          <UserPlus className="w-4 h-4" /> Create User
        </button>
      </div>

      {/* Create form */}
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

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-text-muted pointer-events-none" />
        <input type="text" placeholder="Search by email, name, or role..."
          value={search} onChange={e => setSearch(e.target.value)}
          className="input-field w-full pl-10 pr-9 text-sm" />
        {search && (
          <button onClick={() => setSearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-primary">
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* User list */}
      {filtered.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">{search ? `No users matching "${search}"` : 'No users yet.'}</p>
      ) : (
        <div className="space-y-1">
          {filtered.map(u => (
            <div key={u.id} className="card">
              {/* User row — click to expand */}
              <div className="p-3 flex items-center justify-between cursor-pointer hover:bg-surface-el/50 rounded-lg transition-colors"
                onClick={() => setExpanded(expanded === u.id ? null : u.id)}>
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-primary text-sm font-medium">
                    {(u.displayName || u.email || '?')[0].toUpperCase()}
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-text-primary">{u.displayName || u.email}</span>
                      <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${ROLE_COLORS[u.role] || ''}`}>{u.role}</span>
                      {!u.enabled && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-danger/10 text-danger">DISABLED</span>}
                      {u.mfaEnabled && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-success/10 text-success">MFA</span>}
                    </div>
                    <div className="text-[10px] text-text-muted">{u.email}</div>
                  </div>
                </div>
                <ChevronDown className={`w-4 h-4 text-text-muted transition-transform ${expanded === u.id ? 'rotate-180' : ''}`} />
              </div>

              {/* Expanded edit area */}
              {expanded === u.id && (
                <div className="border-t border-border px-4 py-3 space-y-3">
                  <div className="grid grid-cols-3 gap-3 text-xs">
                    <div>
                      <span className="text-text-muted">Email:</span>
                      <span className="text-text-primary ml-1">{u.email}</span>
                    </div>
                    <div>
                      <span className="text-text-muted">Joined:</span>
                      <span className="text-text-primary ml-1">{new Date(u.createdAt).toLocaleDateString()}</span>
                    </div>
                    <div>
                      <span className="text-text-muted">Last login:</span>
                      <span className="text-text-primary ml-1">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleDateString() : 'Never'}</span>
                    </div>
                  </div>

                  <div className="flex items-center gap-3">
                    <div>
                      <label className="text-xs text-text-muted mb-1 block">Role</label>
                      <select value={u.role} onChange={e => handleRoleChange(u.id, e.target.value)}
                        className="input-field text-xs py-1">
                        {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                      </select>
                    </div>

                    {u.accountStatus === 'INVITED' && (
                      <button onClick={() => handleResendInvite(u.id)}
                        className="btn-secondary text-xs flex items-center gap-1 mt-4">
                        <Mail className="w-3.5 h-3.5" /> Resend Invite
                      </button>
                    )}

                    {u.enabled ? (
                      <button onClick={() => handleToggleEnabled(u.id, false)}
                        className="btn-secondary text-xs text-danger flex items-center gap-1 mt-4">
                        <Ban className="w-3.5 h-3.5" /> Disable
                      </button>
                    ) : (
                      <button onClick={() => handleToggleEnabled(u.id, true)}
                        className="btn-secondary text-xs text-success flex items-center gap-1 mt-4">
                        <CheckCircle2 className="w-3.5 h-3.5" /> Enable
                      </button>
                    )}

                    <button onClick={() => handleDeleteUser(u.id, u.email)}
                      className="btn-secondary text-xs text-danger flex items-center gap-1 mt-4 ml-auto">
                      <Trash2 className="w-3.5 h-3.5" /> Delete
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
