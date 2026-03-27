import { useState, useEffect } from 'react';
import { Loader2, Plus, Trash2, Users, X, Search } from 'lucide-react';
import api from '../api/client';

export default function TeamsManagementTab() {
  const [teams, setTeams] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [newTeam, setNewTeam] = useState({ name: '', description: '' });
  const [creating, setCreating] = useState(false);
  const [expanded, setExpanded] = useState(null);
  const [members, setMembers] = useState([]);
  const [userSearch, setUserSearch] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [addingUser, setAddingUser] = useState(null);

  const fetchTeams = () => { api.get('/teams').then(r => setTeams(r.data || [])).catch(() => {}).finally(() => setLoading(false)); };
  useEffect(() => { fetchTeams(); }, []);

  const handleCreate = async () => {
    if (!newTeam.name.trim()) return;
    setCreating(true);
    try {
      await api.post('/teams', newTeam);
      setNewTeam({ name: '', description: '' });
      setShowCreate(false);
      fetchTeams();
    } catch (e) { alert(e.response?.data?.message || 'Failed'); }
    finally { setCreating(false); }
  };

  const handleDelete = async (id, name) => {
    if (!confirm(`Delete team "${name}"? Members won't be deleted.`)) return;
    try { await api.delete(`/teams/${id}`); fetchTeams(); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  const handleExpand = async (teamId) => {
    if (expanded === teamId) { setExpanded(null); return; }
    setExpanded(teamId);
    api.get(`/teams/${teamId}/members`).then(r => setMembers(r.data || [])).catch(() => setMembers([]));
  };

  const handleSearchUsers = async (q) => {
    setUserSearch(q);
    if (q.length < 2) { setSearchResults([]); return; }
    try {
      const res = await api.get(`/admin/users/search?q=${encodeURIComponent(q)}`);
      setSearchResults(res.data || []);
    } catch { setSearchResults([]); }
  };

  const handleAddMember = async (teamId, userId) => {
    setAddingUser(userId);
    try {
      await api.post(`/teams/${teamId}/members?userId=${userId}`);
      api.get(`/teams/${teamId}/members`).then(r => setMembers(r.data || []));
      fetchTeams();
      setUserSearch('');
      setSearchResults([]);
    } catch (e) { alert(e.response?.data?.message || 'Failed'); }
    finally { setAddingUser(null); }
  };

  const handleRemoveMember = async (teamId, userId) => {
    try {
      await api.delete(`/teams/${teamId}/members/${userId}`);
      api.get(`/teams/${teamId}/members`).then(r => setMembers(r.data || []));
      fetchTeams();
    } catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-8"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-text-primary">Teams</h2>
          <p className="text-sm text-text-muted">Logical groups of users. Add a team to a matter to bulk-assign members.</p>
        </div>
        <button onClick={() => setShowCreate(!showCreate)} className="btn-primary text-sm flex items-center gap-1.5">
          <Plus className="w-4 h-4" /> New Team
        </button>
      </div>

      {showCreate && (
        <div className="card p-4 border-primary/30">
          <div className="grid grid-cols-2 gap-3 mb-3">
            <div>
              <label className="text-xs text-text-muted mb-1 block">Team Name *</label>
              <input value={newTeam.name} onChange={e => setNewTeam({ ...newTeam, name: e.target.value })}
                placeholder="e.g. Corporate Team" className="input-field w-full text-sm" />
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Description</label>
              <input value={newTeam.description} onChange={e => setNewTeam({ ...newTeam, description: e.target.value })}
                placeholder="Handles M&A, corporate governance..." className="input-field w-full text-sm" />
            </div>
          </div>
          <div className="flex gap-2">
            <button onClick={handleCreate} disabled={creating || !newTeam.name.trim()} className="btn-primary text-sm">
              {creating ? 'Creating...' : 'Create Team'}
            </button>
            <button onClick={() => setShowCreate(false)} className="btn-secondary text-sm">Cancel</button>
          </div>
        </div>
      )}

      {teams.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No teams created yet.</p>
      ) : (
        <div className="space-y-2">
          {teams.map(t => (
            <div key={t.id} className="card">
              <div className="flex items-center justify-between p-3 cursor-pointer" onClick={() => handleExpand(t.id)}>
                <div className="flex items-center gap-3">
                  <Users className="w-5 h-5 text-primary" />
                  <div>
                    <span className="font-medium text-text-primary">{t.name}</span>
                    {t.description && <span className="text-xs text-text-muted ml-2">{t.description}</span>}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-text-muted">{t.memberCount} members</span>
                  <button onClick={e => { e.stopPropagation(); handleDelete(t.id, t.name); }}
                    className="text-text-muted hover:text-danger p-1"><Trash2 className="w-3.5 h-3.5" /></button>
                </div>
              </div>

              {expanded === t.id && (
                <div className="border-t border-border p-3 space-y-3">
                  {/* Search + add member */}
                  <div className="relative">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-text-muted pointer-events-none" />
                    <input
                      type="text"
                      placeholder="Search users by email or name..."
                      value={userSearch}
                      onChange={e => handleSearchUsers(e.target.value)}
                      className="input-field w-full pl-10 text-sm"
                    />
                    {userSearch && (
                      <button onClick={() => { setUserSearch(''); setSearchResults([]); }}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-primary">
                        <X className="w-4 h-4" />
                      </button>
                    )}
                  </div>

                  {searchResults.length > 0 && (
                    <div className="border border-border rounded-lg divide-y divide-border max-h-40 overflow-y-auto">
                      {searchResults.map(u => {
                        const alreadyMember = members.some(m => m.userId === u.id);
                        return (
                          <div key={u.id} className="flex items-center justify-between px-3 py-2">
                            <div className="text-sm">
                              <span className="text-text-primary">{u.displayName || u.email}</span>
                              {u.displayName && <span className="text-text-muted ml-1 text-xs">{u.email}</span>}
                            </div>
                            {alreadyMember ? (
                              <span className="text-[10px] text-text-muted">Already in team</span>
                            ) : (
                              <button onClick={() => handleAddMember(t.id, u.id)} disabled={addingUser === u.id}
                                className="btn-primary text-[10px] px-2 py-0.5">
                                {addingUser === u.id ? '...' : '+ Add'}
                              </button>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {/* Member list */}
                  {members.length === 0 ? (
                    <p className="text-text-muted text-xs text-center py-2">No members in this team.</p>
                  ) : (
                    <div className="space-y-1">
                      {members.map(m => (
                        <div key={m.id} className="flex items-center justify-between px-2 py-1.5 rounded hover:bg-surface-el">
                          <div className="flex items-center gap-2">
                            <div className="w-6 h-6 rounded-full bg-primary/10 flex items-center justify-center text-primary text-[10px] font-medium">
                              {(m.displayName || m.email || '?')[0].toUpperCase()}
                            </div>
                            <div>
                              <span className="text-sm text-text-primary">{m.displayName || m.email}</span>
                              <span className="text-[10px] text-text-muted ml-1">{m.role}</span>
                            </div>
                          </div>
                          <button onClick={() => handleRemoveMember(t.id, m.userId)}
                            className="text-text-muted hover:text-danger"><Trash2 className="w-3 h-3" /></button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
