import { useState, useEffect } from 'react';
import { Download, ChevronLeft, ChevronRight } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

const ROLES = [
  { value: '', label: 'All Roles' },
  { value: 'ROLE_ADMIN', label: 'Admin' },
  { value: 'ROLE_PARTNER', label: 'Partner' },
  { value: 'ROLE_ASSOCIATE', label: 'Associate' },
];

const ACTION_GROUPS = [
  { label: 'Documents', actions: ['DOCUMENT_UPLOAD', 'DOCUMENT_VIEW', 'DOCUMENT_DELETE'] },
  { label: 'AI & Analysis', actions: ['AI_QUERY', 'AI_COMPARE', 'RISK_ASSESSMENT'] },
  { label: 'Workflows', actions: ['WORKFLOW_STARTED', 'WORKFLOW_COMPLETED', 'WORKFLOW_FAILED', 'WORKFLOW_CANCELLED'] },
  { label: 'Notifications', actions: ['CONNECTOR_EMAIL_SENT', 'CONNECTOR_SLACK_SENT', 'CONNECTOR_TEAMS_SENT', 'CONNECTOR_WEBHOOK_SENT'] },
  { label: 'AI Agent', actions: ['AGENT_ANALYSIS_TRIGGERED', 'AGENT_ANALYSIS_COMPLETED', 'AGENT_FINDING_REVIEWED'] },
  { label: 'Matters', actions: ['MATTER_CREATED', 'MATTER_UPDATED', 'MATTER_STATUS_CHANGED', 'MATTER_MEMBER_ADDED', 'MATTER_MEMBER_REMOVED'] },
  { label: 'Playbooks', actions: ['PLAYBOOK_CREATED', 'PLAYBOOK_UPDATED', 'PLAYBOOK_DELETED'] },
  { label: 'Users', actions: ['USER_CREATED', 'USER_DELETED', 'USER_ROLE_CHANGED', 'USER_ENABLED', 'USER_DISABLED', 'USER_INVITE_SENT'] },
  { label: 'Integrations', actions: ['INTEGRATION_CONNECTED', 'INTEGRATION_DISCONNECTED'] },
  { label: 'Authentication', actions: ['LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGOUT', 'PASSWORD_CHANGED', 'MFA_ENABLED', 'MFA_DISABLED', 'ACCOUNT_LOCKED'] },
];

function getRelativeDate(offset) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toISOString().split('T')[0];
}
const QUICK_FILTERS = [
  { label: 'Today', from: () => getRelativeDate(0), to: () => getRelativeDate(0) },
  { label: 'Yesterday', from: () => getRelativeDate(-1), to: () => getRelativeDate(-1) },
  { label: 'Last 7 days', from: () => getRelativeDate(-7), to: () => getRelativeDate(0) },
  { label: 'Last 30 days', from: () => getRelativeDate(-30), to: () => getRelativeDate(0) },
  { label: 'All time', from: () => '', to: () => '' },
];

function toInstantParam(dateStr) {
  if (!dateStr) return null;
  return `${dateStr}T00:00:00Z`;
}
function toInstantParamEnd(dateStr) {
  if (!dateStr) return null;
  return `${dateStr}T23:59:59Z`;
}

export default function AuditLogPage({ embedded = false }) {
  const [logs, setLogs] = useState([]);
  const [stats, setStats] = useState(null);
  const [users, setUsers] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [userFilter, setUserFilter] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [actionFilter, setActionFilter] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState('');

  const fetchLogs = () => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(page), size: '15' });
    if (userFilter) params.set('user', userFilter);
    if (roleFilter) params.set('role', roleFilter);
    if (actionFilter) params.set('action', actionFilter);
    const from = toInstantParam(fromDate);
    const to = toInstantParamEnd(toDate);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    api.get(`/audit/logs?${params}`)
      .then(r => {
        const data = r.data;
        const content = Array.isArray(data?.content) ? data.content : (Array.isArray(data) ? data : []);
        const pages = data?.totalPages ?? data?.page?.totalPages ?? 0;
        setLogs(content);
        setTotalPages(Number(pages) || 0);
        setFetchError('');
      })
      .catch(err => {
        console.error('Audit logs fetch failed:', err?.response?.status, err?.response?.data);
        setFetchError(err?.response?.data?.message || 'Failed to load audit logs');
        setLogs([]);
      })
      .finally(() => setLoading(false));
  };

  const fetchStats = () => {
    const params = new URLSearchParams();
    const from = toInstantParam(fromDate);
    const to = toInstantParamEnd(toDate);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    api.get(`/audit/stats?${params}`).then(r => setStats(r.data)).catch(() => {});
  };

  const fetchUsers = () => {
    const params = new URLSearchParams();
    const from = toInstantParam(fromDate);
    const to = toInstantParamEnd(toDate);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    api.get(`/audit/users?${params}`).then(r => setUsers(r.data || [])).catch(() => setUsers([]));
  };

  useEffect(() => { fetchLogs(); fetchStats(); fetchUsers(); }, [page, userFilter, roleFilter, actionFilter, fromDate, toDate]);

  const handleExport = () => {
    const params = new URLSearchParams();
    if (userFilter) params.set('user', userFilter);
    if (roleFilter) params.set('role', roleFilter);
    if (actionFilter) params.set('action', actionFilter);
    const from = toInstantParam(fromDate);
    const to = toInstantParamEnd(toDate);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    api.get(`/audit/export?${params}`, { responseType: 'blob' })
      .then(r => {
        const url = URL.createObjectURL(new Blob([r.data]));
        const a = document.createElement('a');
        a.href = url;
        a.download = `audit_log_${new Date().toISOString().split('T')[0]}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch(err => alert(err.response?.data?.message || err.message || 'Export failed'));
  };

  const statCards = [
    { label: 'Total Actions', value: stats?.totalActions || 0 },
    { label: 'Uploads', value: stats?.uploads || 0 },
    { label: 'Queries', value: stats?.queries || 0 },
    { label: 'Exports', value: (stats?.comparisons || 0) + (stats?.riskAssessments || 0) },
  ];

  return (
    <div>
      {!embedded && <h1 className="text-2xl font-bold mb-6">Audit Log</h1>}

      <div className="grid grid-cols-4 gap-4 mb-6">
        {statCards.map(s => (
          <div key={s.label} className="card">
            <p className="text-2xl font-bold text-text-primary">{s.value}</p>
            <p className="text-sm text-text-muted">{s.label}</p>
          </div>
        ))}
      </div>

      {fetchError && (
        <div className="card mb-4 border-l-4 border-danger bg-danger/5">
          <p className="text-danger text-sm">{fetchError}</p>
        </div>
      )}

      <div className="card mb-4">
        <p className="text-xs text-text-muted mb-3">Filter by user, role, action type, and date range. Export uses current filters.</p>
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="text-xs text-text-muted mb-1 block">User</label>
            <select value={userFilter} onChange={e => { setUserFilter(e.target.value); setPage(0); }} className="input-field text-sm min-w-[120px]">
              <option value="">All Users</option>
              {users.map(u => <option key={u} value={u}>{u}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Role</label>
            <select value={roleFilter} onChange={e => { setRoleFilter(e.target.value); setPage(0); }} className="input-field text-sm min-w-[120px]">
              {ROLES.map(r => <option key={r.value || 'all'} value={r.value}>{r.label}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Action</label>
            <select value={actionFilter} onChange={e => { setActionFilter(e.target.value); setPage(0); }} className="input-field text-sm min-w-[160px]">
              <option value="">All Actions</option>
              {ACTION_GROUPS.map(g => (
                <optgroup key={g.label} label={g.label}>
                  {g.actions.map(a => <option key={a} value={a}>{a.replace(/_/g, ' ')}</option>)}
                </optgroup>
              ))}
            </select>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Quick filter</label>
            <div className="flex gap-1">
              {QUICK_FILTERS.map(qf => (
                <button key={qf.label}
                  onClick={() => { setFromDate(qf.from()); setToDate(qf.to()); setPage(0); }}
                  className={`text-[10px] px-2 py-1 rounded-full border transition-colors ${
                    fromDate === qf.from() && toDate === qf.to()
                      ? 'bg-primary/10 border-primary/30 text-primary'
                      : 'border-border text-text-muted hover:border-primary/30 hover:text-text-primary'
                  }`}>
                  {qf.label}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">From</label>
            <input type="date" value={fromDate} onChange={e => { setFromDate(e.target.value); setPage(0); }} className="input-field text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">To</label>
            <input type="date" value={toDate} onChange={e => { setToDate(e.target.value); setPage(0); }} className="input-field text-sm" />
          </div>
          <button onClick={handleExport} className="btn-secondary text-sm flex items-center gap-2 ml-auto" title="Export filtered results to CSV">
            <Download className="w-4 h-4" /> Export CSV
          </button>
        </div>
      </div>

      {loading ? <LoadingSkeleton rows={8} /> : (
        <div className="card overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-text-muted">
                <th className="pb-3 font-medium">Time</th>
                <th className="pb-3 font-medium">User</th>
                <th className="pb-3 font-medium">Role</th>
                <th className="pb-3 font-medium">Action</th>
                <th className="pb-3 font-medium">Endpoint</th>
                <th className="pb-3 font-medium">Status</th>
                <th className="pb-3 font-medium">Duration</th>
              </tr>
            </thead>
            <tbody>
              {logs.length === 0 ? (
                <tr><td colSpan={7} className="py-12 text-center text-text-muted">No audit logs found.</td></tr>
              ) : logs.map((l, i) => (
                <tr key={l.id || `log-${i}`} className="border-b border-border/50 hover:bg-surface-el/50 transition-colors">
                  <td className="py-3 text-text-muted">{l.timestamp ? new Date(l.timestamp).toLocaleString() : '—'}</td>
                  <td className="py-3">{l.username ?? '—'}</td>
                  <td className="py-3 text-text-secondary">{l.userRole ? l.userRole.replace('ROLE_', '') : '—'}</td>
                  <td className="py-3">
                    <span className="bg-surface-el px-2 py-0.5 rounded text-xs">{l.action ? String(l.action).replace(/_/g, ' ') : '—'}</span>
                  </td>
                  <td className="py-3 font-mono text-xs text-text-secondary">{l.endpoint ?? '—'}</td>
                  <td className="py-3">{l.success
                    ? <span className="text-success text-xs">OK</span>
                    : <span className="text-danger text-xs">FAIL</span>}</td>
                  <td className="py-3 text-text-muted">{l.responseTimeMs != null ? `${l.responseTimeMs}ms` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {totalPages > 1 && (
            <div className="flex items-center justify-between mt-4 pt-4 border-t border-border">
              <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
                className="btn-secondary text-xs py-1.5 flex items-center gap-1"><ChevronLeft className="w-3 h-3" /> Prev</button>
              <span className="text-xs text-text-muted">Page {page + 1} of {totalPages}</span>
              <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
                className="btn-secondary text-xs py-1.5 flex items-center gap-1">Next <ChevronRight className="w-3 h-3" /></button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
