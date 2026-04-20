import { useEffect, useState, useRef } from 'react';
import { Loader, CheckCircle2, XCircle, Clock } from 'lucide-react';
import api from '../api/client';

/**
 * Compact strip of the user's recent background-drafted contracts. Polls
 * /api/v1/ai/drafts every 5 s while any entry is still in flight.
 *
 * Click an entry → `onSelect(id)` — the parent loads that draft into the
 * right panel (either the live progress view or the finished document).
 *
 * Exposes a `refresh()` imperative via `onReady({ refresh })` so the
 * parent can force-refresh after submitting a new async draft.
 */
export default function DraftsRecentStrip({ onSelect, activeId, onReady }) {
  const [drafts, setDrafts] = useState([]);
  const [loading, setLoading] = useState(true);
  const pollRef = useRef(null);

  const fetchList = async () => {
    try {
      const r = await api.get('/ai/drafts');
      setDrafts(Array.isArray(r.data) ? r.data : []);
    } catch {
      // Leave last-known list on transient fetch error
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchList();
    if (onReady) onReady({ refresh: fetchList });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Poll every 5 s while any draft is PENDING or PROCESSING.
  useEffect(() => {
    const anyInFlight = drafts.some(d => d.status === 'PENDING' || d.status === 'PROCESSING');
    if (!anyInFlight) {
      if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
      return;
    }
    if (!pollRef.current) {
      pollRef.current = setInterval(fetchList, 5000);
    }
    return () => {
      if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
    };
  }, [drafts]);

  if (loading) {
    return (
      <div className="card text-xs text-text-muted py-3 flex items-center gap-2">
        <Loader className="w-3 h-3 animate-spin" /> Loading your recent drafts…
      </div>
    );
  }

  if (drafts.length === 0) return null;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold text-text-secondary uppercase tracking-wide">
          Your recent drafts
        </span>
        <span className="text-[10px] text-text-muted">
          {drafts.some(d => d.status === 'PENDING' || d.status === 'PROCESSING')
            ? 'Auto-updating…'
            : `${drafts.length} saved`}
        </span>
      </div>
      <div className="flex flex-col divide-y divide-border/50">
        {drafts.map(d => {
          const statusIcon = {
            PENDING:    <Clock className="w-4 h-4 text-text-muted" />,
            PROCESSING: <Loader className="w-4 h-4 text-primary animate-spin" />,
            INDEXED:    <CheckCircle2 className="w-4 h-4 text-success" />,
            FAILED:     <XCircle className="w-4 h-4 text-danger" />,
          }[d.status] || <Clock className="w-4 h-4 text-text-muted" />;

          const progressLabel = d.status === 'PROCESSING' && d.totalClauses
            ? `${d.completedClauses || 0}/${d.totalClauses}${d.currentClauseLabel ? ` — ${d.currentClauseLabel}` : ''}`
            : d.status === 'INDEXED'
              ? 'Ready'
              : d.status === 'FAILED'
                ? (d.errorMessage ? `Failed — ${truncate(d.errorMessage, 60)}` : 'Failed')
                : 'Pending…';

          const isActive = activeId === d.id;

          return (
            <button
              key={d.id}
              type="button"
              onClick={() => onSelect?.(d.id)}
              className={`flex items-center gap-3 py-2 px-2 rounded text-left transition-colors ${
                isActive ? 'bg-primary/10 ring-1 ring-primary/30' : 'hover:bg-surface-el/50'
              }`}
            >
              <span className="shrink-0">{statusIcon}</span>
              <span className="flex-1 min-w-0">
                <span className="block text-sm text-text-primary truncate">{d.fileName}</span>
                <span className="block text-xs text-text-muted truncate">{progressLabel}</span>
              </span>
              <span className="text-[10px] text-text-muted tabular-nums shrink-0">
                {d.createdAt ? new Date(d.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''}
                {d.durationSeconds ? ` (${Math.floor(d.durationSeconds / 60)}m${d.durationSeconds % 60}s)` : ''}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function truncate(s, max) {
  if (!s) return '';
  return s.length <= max ? s : s.slice(0, max) + '…';
}
