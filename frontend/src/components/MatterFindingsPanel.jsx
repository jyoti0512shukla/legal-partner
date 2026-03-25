import { useState, useEffect, useCallback } from 'react';
import { Loader2, CheckCircle2, Flag, AlertTriangle, FileText } from 'lucide-react';
import api from '../api/client';

const SEVERITY_STYLES = {
  HIGH: { bg: 'bg-danger/10', border: 'border-danger/20', text: 'text-danger', dot: '🔴' },
  MEDIUM: { bg: 'bg-warning/10', border: 'border-warning/20', text: 'text-warning', dot: '🟡' },
  LOW: { bg: 'bg-success/10', border: 'border-success/20', text: 'text-success', dot: '🟢' },
};

const STATUS_STYLES = {
  NEW: 'bg-primary/10 text-primary',
  REVIEWED: 'bg-surface-el text-text-muted',
  ACCEPTED: 'bg-success/10 text-success',
  FLAGGED: 'bg-danger/10 text-danger',
};

export default function MatterFindingsPanel({ matterId }) {
  const [findings, setFindings] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [updating, setUpdating] = useState(null);

  const fetchData = useCallback(() => {
    Promise.all([
      api.get(`/matters/${matterId}/findings`),
      api.get(`/matters/${matterId}/findings/summary`),
    ]).then(([findingsRes, summaryRes]) => {
      setFindings(findingsRes.data || []);
      setSummary(summaryRes.data);
    }).catch(() => {})
      .finally(() => setLoading(false));
  }, [matterId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // Poll every 30s
  useEffect(() => {
    const interval = setInterval(fetchData, 30000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const handleReview = async (findingId, status) => {
    setUpdating(findingId);
    try {
      await api.patch(`/matters/${matterId}/findings/${findingId}`, { status });
      fetchData();
    } catch (e) {
      alert('Failed to update finding');
    } finally {
      setUpdating(null);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-text-muted py-4">
        <Loader2 className="w-4 h-4 animate-spin" /> Analyzing...
      </div>
    );
  }

  if (findings.length === 0) {
    return (
      <div className="text-center py-6">
        <AlertTriangle className="w-8 h-8 text-text-muted mx-auto mb-2" />
        <p className="text-text-muted text-sm">No findings yet. Upload a document to this matter to trigger analysis.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Summary bar */}
      {summary && (
        <div className="flex items-center gap-4 text-xs">
          <span className="flex items-center gap-1 text-danger font-medium">🔴 {summary.highCount} HIGH</span>
          <span className="flex items-center gap-1 text-warning font-medium">🟡 {summary.mediumCount} MEDIUM</span>
          <span className="flex items-center gap-1 text-success font-medium">🟢 {summary.lowCount} LOW</span>
          <span className="text-text-muted">|</span>
          <span className="text-text-muted">{summary.unreviewedCount} unreviewed</span>
        </div>
      )}

      {/* Findings list */}
      <div className="space-y-2">
        {findings.map(f => {
          const sev = SEVERITY_STYLES[f.severity] || SEVERITY_STYLES.MEDIUM;
          return (
            <div key={f.id} className={`border ${sev.border} ${sev.bg} rounded-lg p-3`}>
              <div className="flex items-start justify-between gap-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span>{sev.dot}</span>
                    <span className="text-sm font-medium text-text-primary">{f.title}</span>
                    {f.clauseType && (
                      <span className="text-[10px] bg-surface-el border border-border/50 px-1.5 py-0.5 rounded-full text-text-muted">
                        {f.clauseType}
                      </span>
                    )}
                    <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${STATUS_STYLES[f.status] || ''}`}>
                      {f.status}
                    </span>
                  </div>
                  <p className="text-xs text-text-secondary mb-1">{f.description}</p>
                  <div className="flex items-center gap-3 text-[10px] text-text-muted">
                    {f.documentName && (
                      <span className="flex items-center gap-1"><FileText className="w-3 h-3" /> {f.documentName}</span>
                    )}
                    {f.sectionRef && <span>§ {f.sectionRef}</span>}
                    {f.relatedDocumentName && <span>Conflicts with: {f.relatedDocumentName}</span>}
                  </div>
                </div>

                {f.status === 'NEW' && (
                  <div className="flex gap-1 shrink-0">
                    <button
                      onClick={() => handleReview(f.id, 'ACCEPTED')}
                      disabled={updating === f.id}
                      className="text-success hover:bg-success/10 p-1.5 rounded-lg transition-colors"
                      title="Acceptable"
                    >
                      <CheckCircle2 className="w-4 h-4" />
                    </button>
                    <button
                      onClick={() => handleReview(f.id, 'FLAGGED')}
                      disabled={updating === f.id}
                      className="text-danger hover:bg-danger/10 p-1.5 rounded-lg transition-colors"
                      title="Flag for negotiation"
                    >
                      <Flag className="w-4 h-4" />
                    </button>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
