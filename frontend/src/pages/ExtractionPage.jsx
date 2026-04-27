import { useState, useEffect } from 'react';
import { Key, Loader2, RefreshCw, AlertTriangle, CheckCircle2, ShieldAlert, Info } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';
import BucketSection, { BUCKET_ORDER } from '../components/extraction/BucketSection';

export default function ExtractionPage() {
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState('');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/documents?size=100&sort=uploadDate,desc')
      .then(r => setDocs(r.data.content || []))
      .catch(() => {});
  }, []);

  const handleExtract = async (regenerate = false) => {
    if (!docId) return;
    setLoading(true); setError(''); if (regenerate) setResult(null);
    try {
      const res = await api.post(`/ai/extract/${docId}${regenerate ? '?regenerate=true' : ''}`);
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Extraction failed');
    } finally { setLoading(false); }
  };

  const handleCorrect = async (field, value) => {
    try {
      const res = await api.post(`/ai/extract/${docId}/correct`, { field, value });
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Correction failed');
    }
  };

  // Auto-load cached result when document changes
  useEffect(() => {
    if (!docId) { setResult(null); return; }
    setResult(null); setError('');
    api.post(`/ai/extract/${docId}`)
      .then(r => { if (r.data?.entries) setResult(r.data); })
      .catch(() => {});
  }, [docId]);

  // Group entries by bucket
  const bucketGroups = {};
  if (result?.entries) {
    for (const entry of result.entries) {
      const bucket = entry.bucket || 'OTHER';
      if (!bucketGroups[bucket]) bucketGroups[bucket] = [];
      bucketGroups[bucket].push(entry);
    }
  }
  const sortedBuckets = BUCKET_ORDER.filter(b => bucketGroups[b]?.length > 0);
  // Add any extra buckets not in the predefined order
  Object.keys(bucketGroups).filter(b => !sortedBuckets.includes(b)).forEach(b => sortedBuckets.push(b));

  const selectedDoc = docs.find(d => d.id === docId);
  const ct = result?.contractTypeDetection;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Key Terms Extraction</h1>
          <p className="page-sub">AI discovers material terms, validates with evidence, scores confidence.</p>
        </div>
      </div>

      {/* Document selector + actions */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'end', gap: 12 }}>
          <div style={{ flex: 1 }}>
            <label className="tiny muted" style={{ display: 'block', marginBottom: 4 }}>Select Document</label>
            <select value={docId} onChange={e => setDocId(e.target.value)} className="select" style={{ width: '100%' }}>
              <option value="">Choose a contract...</option>
              {docs.map(d => (
                <option key={d.id} value={d.id}>
                  {d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}{d.documentType ? ` [${d.documentType}]` : ''}
                </option>
              ))}
            </select>
          </div>
          {result && (
            <button className="btn" onClick={() => handleExtract(true)} disabled={loading}>
              {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
              Re-extract
            </button>
          )}
          {!result && docId && (
            <button className="btn primary" onClick={() => handleExtract(false)} disabled={loading}>
              {loading ? <Loader2 size={14} className="animate-spin" /> : <Key size={14} />}
              {loading ? 'Extracting...' : 'Extract Key Terms'}
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="card" style={{ borderColor: 'rgba(217,83,78,0.3)', background: 'var(--danger-bg)', padding: 14, marginBottom: 14 }}>
          <span className="small" style={{ color: 'var(--danger-400)' }}>{error}</span>
        </div>
      )}

      {loading && !result && <LoadingSkeleton rows={12} />}

      {result && (
        <>
          {/* Stats bar */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 16 }}>
            <div className="card" style={{ padding: 14, textAlign: 'center' }}>
              <div style={{ fontSize: 22, fontWeight: 600, fontFamily: 'var(--font-doc)' }}>{result.totalFieldsDiscovered || 0}</div>
              <div className="tiny muted" style={{ textTransform: 'uppercase', letterSpacing: '0.06em' }}>Discovered</div>
            </div>
            <div className="card" style={{ padding: 14, textAlign: 'center' }}>
              <div style={{ fontSize: 22, fontWeight: 600, fontFamily: 'var(--font-doc)', color: 'var(--success-400)' }}>{result.totalFieldsValidated || 0}</div>
              <div className="tiny muted" style={{ textTransform: 'uppercase', letterSpacing: '0.06em' }}>Validated</div>
            </div>
            <div className="card" style={{ padding: 14, textAlign: 'center' }}>
              <div style={{ fontSize: 22, fontWeight: 600, fontFamily: 'var(--font-doc)', color: 'var(--warn-400)' }}>{result.totalGaps || 0}</div>
              <div className="tiny muted" style={{ textTransform: 'uppercase', letterSpacing: '0.06em' }}>Gaps</div>
            </div>
            <div className="card" style={{ padding: 14, textAlign: 'center' }}>
              {ct && (
                <>
                  <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--brand-400)' }}>
                    {ct.contractType?.replace('_', ' ')}
                  </div>
                  <div className="tiny muted">
                    {Math.round(ct.confidence * 100)}% confidence
                    {ct.signals?.length > 0 && (
                      <span style={{ display: 'block', fontSize: 9, marginTop: 2 }}>
                        {ct.signals.slice(0, 3).join(', ')}
                      </span>
                    )}
                  </div>
                </>
              )}
            </div>
          </div>

          {/* Top risks */}
          {result.topRisks?.length > 0 && (
            <div className="card" style={{ marginBottom: 16, padding: 14, borderLeft: '3px solid var(--danger-400)' }}>
              <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
                <ShieldAlert size={14} style={{ color: 'var(--danger-400)' }} /> Top Risks
              </div>
              {result.topRisks.map((risk, i) => (
                <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', marginBottom: 6 }}>
                  <span style={{
                    fontSize: 10, fontWeight: 600, padding: '1px 6px', borderRadius: 999, flexShrink: 0,
                    background: risk.severity === 'HIGH' ? 'var(--danger-bg)' : risk.severity === 'MEDIUM' ? 'var(--warn-bg)' : 'var(--bg-3)',
                    color: risk.severity === 'HIGH' ? 'var(--danger-400)' : risk.severity === 'MEDIUM' ? 'var(--warn-400)' : 'var(--text-4)',
                  }}>
                    {risk.severity}
                  </span>
                  <div>
                    <span className="small" style={{ fontWeight: 500 }}>{risk.risk}</span>
                    {risk.explanation && (
                      <span className="small muted" style={{ marginLeft: 6 }}>— {risk.explanation}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Consistency issues */}
          {result.consistencyIssues?.length > 0 && (
            <div className="card" style={{ marginBottom: 16, padding: 14, borderLeft: '3px solid var(--warn-400)', background: 'var(--warn-bg)' }}>
              <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
                <AlertTriangle size={14} style={{ color: 'var(--warn-400)' }} /> Consistency Issues
              </div>
              {result.consistencyIssues.map((issue, i) => (
                <div key={i} className="small" style={{ color: 'var(--warn-400)', marginBottom: 4 }}>• {issue}</div>
              ))}
            </div>
          )}

          {/* Bucket sections */}
          {sortedBuckets.map(bucket => (
            <BucketSection
              key={bucket}
              bucket={bucket}
              entries={bucketGroups[bucket]}
              onCorrect={handleCorrect}
            />
          ))}
        </>
      )}

      {!result && !loading && !error && !docId && (
        <div className="card" style={{ textAlign: 'center', padding: '48px 24px' }}>
          <Key size={40} style={{ color: 'var(--text-4)', margin: '0 auto 16px' }} />
          <div style={{ fontWeight: 500, marginBottom: 8 }}>Select a document to extract key terms</div>
          <div className="small muted">
            AI discovers all material terms — parties, dates, amounts, obligations, restrictions — with evidence and confidence scoring.
          </div>
        </div>
      )}
    </div>
  );
}
