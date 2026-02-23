import { useState, useEffect } from 'react';
import { ScrollText, Download, ChevronLeft, ChevronRight } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

export default function AuditLogPage() {
  const [logs, setLogs] = useState([]);
  const [stats, setStats] = useState(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [userFilter, setUserFilter] = useState('');
  const [actionFilter, setActionFilter] = useState('');
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState('');

  const fetchLogs = () => {
    setLoading(true);
    const params = new URLSearchParams({ page: String(page), size: '15' });
    if (userFilter) params.set('user', userFilter);
    if (actionFilter) params.set('action', actionFilter);
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
    api.get('/audit/stats').then(r => setStats(r.data)).catch(() => {});
  };

  useEffect(() => { fetchLogs(); fetchStats(); }, [page, userFilter, actionFilter]);

  const handleExport = () => {
    api.get('/audit/export', { responseType: 'blob' }).then(r => {
      const url = URL.createObjectURL(new Blob([r.data]));
      const a = document.createElement('a');
      a.href = url; a.download = `audit_log_${new Date().toISOString().split('T')[0]}.csv`;
      a.click(); URL.revokeObjectURL(url);
    });
  };

  const statCards = [
    { label: 'Total Actions', value: stats?.totalActions || 0 },
    { label: 'Uploads', value: stats?.uploads || 0 },
    { label: 'Queries', value: stats?.queries || 0 },
    { label: 'Exports', value: (stats?.comparisons || 0) + (stats?.riskAssessments || 0) },
  ];

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Audit Log</h1>

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
        <div className="flex items-center gap-3">
          <select value={userFilter} onChange={e => { setUserFilter(e.target.value); setPage(0); }} className="input-field text-sm">
            <option value="">All Users</option>
            {['admin', 'partner', 'associate'].map(u => <option key={u} value={u}>{u}</option>)}
          </select>
          <select value={actionFilter} onChange={e => { setActionFilter(e.target.value); setPage(0); }} className="input-field text-sm">
            <option value="">All Actions</option>
            {['DOCUMENT_UPLOAD', 'DOCUMENT_VIEW', 'DOCUMENT_DELETE', 'AI_QUERY', 'AI_COMPARE', 'RISK_ASSESSMENT', 'AUDIT_VIEW', 'AUDIT_EXPORT'].map(a =>
              <option key={a} value={a}>{a.replace(/_/g, ' ')}</option>
            )}
          </select>
          <button onClick={handleExport} className="btn-secondary text-sm ml-auto flex items-center gap-2">
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
                <th className="pb-3 font-medium">Action</th>
                <th className="pb-3 font-medium">Endpoint</th>
                <th className="pb-3 font-medium">Status</th>
                <th className="pb-3 font-medium">Duration</th>
              </tr>
            </thead>
            <tbody>
              {logs.length === 0 ? (
                <tr><td colSpan={6} className="py-12 text-center text-text-muted">No audit logs found.</td></tr>
              ) : logs.map((l, i) => (
                <tr key={l.id || `log-${i}`} className="border-b border-border/50 hover:bg-surface-el/50 transition-colors">
                  <td className="py-3 text-text-muted">{l.timestamp ? new Date(l.timestamp).toLocaleTimeString() : '—'}</td>
                  <td className="py-3 capitalize">{l.username ?? '—'}</td>
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
