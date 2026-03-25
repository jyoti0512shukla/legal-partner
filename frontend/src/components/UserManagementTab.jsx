import { useState, useEffect } from 'react';
import { Loader2, UserPlus, Ban, CheckCircle2, Mail, Search, X, Trash2, Key, Shield } from 'lucide-react';
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

  const handleRoleChange = async (userId, newRole) => {
    try { await api.patch(`/admin/users/${userId}/role?role=${newRole}`); fetchUsers(); refreshSelected(userId); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleToggleEnabled = async (userId, enabled) => {
    try { await api.patch(`/admin/users/${userId}/enable?enabled=${enabled}`); fetchUsers(); refreshSelected(userId); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleResendInvite = async (userId) => {
    try { await api.post(`/admin/users/${userId}/resend-invite`); alert('Invite sent'); }
    catch (e) { alert(e.response?.data?.message || 'Failed to send invite'); }
  };

  const handleResetPassword = async (userId, email) => {
    try { await api.post(`/admin/users/${userId}/reset-password`); alert(`Password reset email sent to ${email}`); }
    catch (e) { alert(e.response?.data?.message || 'Failed to send reset email'); }
  };

  const handleDeleteUser = async (userId, email) => {
    if (!confirm(`Delete user ${email}? This cannot be undone.`)) return;
    try { await api.delete(`/admin/users/${userId}`); fetchUsers(); setSelectedUser(null); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

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

  const refreshSelected = (userId) => {
    // After an action, refresh the selected user from the list
    setTimeout(() => {
      api.get('/admin/users').then(r => {
        const updated = (r.data || []).find(u => u.id === userId);
        if (updated) setSelectedUser(updated);
      });
    }, 300);
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
            <div key={u.id}
              onClick={() => setSelectedUser(u)}
              className={`card p-3 flex items-center justify-between cursor-pointer transition-colors ${
                selectedUser?.id === u.id ? 'border-primary/50 bg-primary/5' : 'hover:bg-surface-el/50'
              }`}>
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
            </div>
          ))}
        </div>
      )}

      {/* Side panel */}
      {selectedUser && (
        <UserSidePanel
          user={selectedUser}
          onClose={() => setSelectedUser(null)}
          onRoleChange={handleRoleChange}
          onToggleEnabled={handleToggleEnabled}
          onResendInvite={handleResendInvite}
          onResetPassword={handleResetPassword}
          onDelete={handleDeleteUser}
        />
      )}
    </div>
  );
}

function UserSidePanel({ user: u, onClose, onRoleChange, onToggleEnabled, onResendInvite, onResetPassword, onDelete }) {
  return (
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/40" />

      {/* Panel */}
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
            <button onClick={onClose} className="text-text-muted hover:text-text-primary p-1">
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Status badges */}
          <div className="flex items-center gap-2 mb-6">
            <span className={`text-xs px-2.5 py-1 rounded-full font-medium ${ROLE_COLORS[u.role] || ''}`}>{u.role}</span>
            {u.enabled ? (
              <span className="text-xs px-2.5 py-1 rounded-full bg-success/10 text-success">Active</span>
            ) : (
              <span className="text-xs px-2.5 py-1 rounded-full bg-danger/10 text-danger">Disabled</span>
            )}
            {u.mfaEnabled && <span className="text-xs px-2.5 py-1 rounded-full bg-primary/10 text-primary flex items-center gap-1"><Shield className="w-3 h-3" /> MFA</span>}
          </div>

          {/* Details */}
          <div className="space-y-3 mb-6">
            <div className="flex justify-between text-sm">
              <span className="text-text-muted">Joined</span>
              <span className="text-text-primary">{new Date(u.createdAt).toLocaleDateString()}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-text-muted">Last login</span>
              <span className="text-text-primary">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : 'Never'}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-text-muted">Account status</span>
              <span className="text-text-primary">{u.accountStatus}</span>
            </div>
          </div>

          <hr className="border-border mb-6" />

          {/* Actions */}
          <div className="space-y-4">
            {/* Role */}
            <div>
              <label className="text-xs text-text-muted mb-1.5 block">Role</label>
              <select value={u.role} onChange={e => onRoleChange(u.id, e.target.value)}
                className="input-field w-full text-sm">
                {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>

            {/* Action buttons */}
            <div className="space-y-2">
              {/* Resend invite (for invited/disabled users) */}
              {(!u.enabled || u.accountStatus === 'INVITED') && (
                <button onClick={() => onResendInvite(u.id)}
                  className="btn-secondary w-full text-sm flex items-center justify-center gap-2">
                  <Mail className="w-4 h-4" /> Resend Invite Email
                </button>
              )}

              {/* Reset password (for active users) */}
              {u.enabled && u.accountStatus !== 'INVITED' && (
                <button onClick={() => onResetPassword(u.id, u.email)}
                  className="btn-secondary w-full text-sm flex items-center justify-center gap-2">
                  <Key className="w-4 h-4" /> Send Password Reset
                </button>
              )}

              {/* Enable / Disable */}
              {u.enabled ? (
                <button onClick={() => onToggleEnabled(u.id, false)}
                  className="btn-secondary w-full text-sm text-warning flex items-center justify-center gap-2">
                  <Ban className="w-4 h-4" /> Disable Account
                </button>
              ) : (
                <button onClick={() => onToggleEnabled(u.id, true)}
                  className="btn-secondary w-full text-sm text-success flex items-center justify-center gap-2">
                  <CheckCircle2 className="w-4 h-4" /> Enable Account
                </button>
              )}
            </div>

            {/* Danger zone */}
            <div className="pt-4 border-t border-border">
              <p className="text-xs text-text-muted mb-2">Danger Zone</p>
              <button onClick={() => onDelete(u.id, u.email)}
                className="btn-secondary w-full text-sm text-danger flex items-center justify-center gap-2 border-danger/30 hover:bg-danger/10">
                <Trash2 className="w-4 h-4" /> Delete User
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
