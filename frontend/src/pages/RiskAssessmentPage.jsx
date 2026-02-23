import { useState, useEffect } from 'react';
import { ShieldAlert } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

const RISK_COLORS = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' };

export default function RiskAssessmentPage() {
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState('');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/documents?size=100').then(r => setDocs(r.data.content || [])).catch(() => {});
  }, []);

  const handleAssess = async () => {
    if (!docId) return;
    setLoading(true); setError(''); setResult(null);
    try {
      const res = await api.post(`/ai/risk-assessment/${docId}`);
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Assessment failed');
    } finally { setLoading(false); }
  };

  const riskColor = (rating) => RISK_COLORS[rating] || 'text-muted';

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Risk Assessment</h1>

      <div className="card mb-6">
        <div className="flex items-end gap-4">
          <div className="flex-1">
            <label className="text-xs text-text-muted mb-1 block">Select Document</label>
            <select value={docId} onChange={e => setDocId(e.target.value)} className="input-field w-full text-sm">
              <option value="">Choose a contract...</option>
              {docs.map(d => <option key={d.id} value={d.id}>{d.fileName} ({d.jurisdiction}, {d.year})</option>)}
            </select>
          </div>
          <button onClick={handleAssess} disabled={loading || !docId} className="btn-primary flex items-center gap-2">
            {loading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> : <ShieldAlert className="w-4 h-4" />}
            Assess Risk
          </button>
        </div>
      </div>

      {error && <div className="card border-l-4 border-danger bg-danger/5 mb-4"><p className="text-danger text-sm">{error}</p></div>}
      {loading && <LoadingSkeleton rows={8} />}

      {result && (
        <>
          <div className="card text-center py-8 mb-6">
            <h2 className="text-text-muted text-sm mb-3">Overall Risk</h2>
            <RiskGauge rating={result.overallRisk} />
            <p className={`text-2xl font-bold mt-4 text-${riskColor(result.overallRisk)}`}>{result.overallRisk}</p>
            <p className="text-text-muted text-sm mt-1">
              {result.categories?.filter(c => c.rating === 'HIGH').length || 0} High,{' '}
              {result.categories?.filter(c => c.rating === 'MEDIUM').length || 0} Medium,{' '}
              {result.categories?.filter(c => c.rating === 'LOW').length || 0} Low
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {result.categories?.map((cat, i) => (
              <div key={i} className={`card border-t-4 border-${riskColor(cat.rating)}`}>
                <div className="flex items-center justify-between mb-2">
                  <h3 className="font-semibold text-text-primary">{cat.name}</h3>
                  <span className={`badge-${cat.rating.toLowerCase()}`}>{cat.rating}</span>
                </div>
                <p className="text-sm text-text-secondary mb-2">{cat.justification}</p>
                {cat.clauseReference && (
                  <p className="font-mono text-xs text-primary">{cat.clauseReference}</p>
                )}
              </div>
            ))}
          </div>
        </>
      )}

      {!result && !loading && !error && (
        <div className="card text-center py-12">
          <div className="w-20 h-20 mx-auto mb-4 relative">
            <svg viewBox="0 0 100 60" className="w-full h-full">
              <path d="M 10 55 A 45 45 0 0 1 90 55" fill="none" stroke="#374151" strokeWidth="8" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-muted">Select a document to assess its contractual risk</p>
        </div>
      )}
    </div>
  );
}

function RiskGauge({ rating }) {
  const angles = { LOW: -60, MEDIUM: 0, HIGH: 60 };
  const angle = angles[rating] || 0;
  return (
    <div className="w-32 h-20 mx-auto relative">
      <svg viewBox="0 0 100 60" className="w-full h-full">
        <defs>
          <linearGradient id="gauge-bg" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#10B981" />
            <stop offset="50%" stopColor="#F59E0B" />
            <stop offset="100%" stopColor="#EF4444" />
          </linearGradient>
        </defs>
        <path d="M 10 55 A 45 45 0 0 1 90 55" fill="none" stroke="url(#gauge-bg)" strokeWidth="8" strokeLinecap="round" />
        <line x1="50" y1="55" x2={50 + 30 * Math.cos(((angle - 90) * Math.PI) / 180)} y2={55 + 30 * Math.sin(((angle - 90) * Math.PI) / 180)}
          stroke="#F9FAFB" strokeWidth="2" strokeLinecap="round"
          style={{ transition: 'all 0.8s ease-out' }} />
        <circle cx="50" cy="55" r="3" fill="#F9FAFB" />
      </svg>
    </div>
  );
}
