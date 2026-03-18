import { useState, useEffect } from 'react';
import { Key, Copy, CheckCircle } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

const FIELD_LABELS = {
  partyA: 'Party A',
  partyB: 'Party B',
  effectiveDate: 'Effective Date',
  expiryDate: 'Expiry / End Date',
  contractValue: 'Contract Value',
  liabilityCap: 'Liability Cap',
  governingLaw: 'Governing Law',
  noticePeriodDays: 'Notice Period',
  arbitrationVenue: 'Arbitration Venue',
};

export default function ExtractionPage() {
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState('');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.get('/documents?size=100').then(r => setDocs(r.data.content || [])).catch(() => {});
  }, []);

  const handleExtract = async () => {
    if (!docId) return;
    setLoading(true); setError(''); setResult(null);
    try {
      const res = await api.post(`/ai/extract/${docId}`);
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Extraction failed');
    } finally { setLoading(false); }
  };

  const handleCopy = () => {
    if (!result) return;
    const text = Object.entries(FIELD_LABELS)
      .map(([k, label]) => `${label}: ${result[k] || 'Not found'}`)
      .join('\n');
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Key Terms Extraction</h1>

      <div className="card mb-6">
        <div className="flex items-end gap-4">
          <div className="flex-1">
            <label className="text-xs text-text-muted mb-1 block">Select Document</label>
            <select value={docId} onChange={e => setDocId(e.target.value)} className="input-field w-full text-sm">
              <option value="">Choose a contract...</option>
              {docs.map(d => <option key={d.id} value={d.id}>{d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}</option>)}
            </select>
          </div>
          <button onClick={handleExtract} disabled={loading || !docId} className="btn-primary flex items-center gap-2">
            {loading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> : <Key className="w-4 h-4" />}
            Extract Terms
          </button>
        </div>
      </div>

      {error && <div className="card border-l-4 border-danger bg-danger/5 mb-4"><p className="text-danger text-sm">{error}</p></div>}
      {loading && <LoadingSkeleton rows={9} />}

      {result && (
        <div className="card">
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-lg font-semibold">Extracted Key Terms</h2>
            <button onClick={handleCopy} className="flex items-center gap-1.5 text-sm text-text-muted hover:text-primary transition-colors">
              {copied ? <CheckCircle className="w-4 h-4 text-success" /> : <Copy className="w-4 h-4" />}
              {copied ? 'Copied' : 'Copy all'}
            </button>
          </div>
          <div className="grid grid-cols-1 divide-y divide-border">
            {Object.entries(FIELD_LABELS).map(([key, label]) => (
              <div key={key} className="flex items-start py-3 gap-4">
                <span className="text-sm text-text-muted w-44 shrink-0">{label}</span>
                <span className={`text-sm font-medium ${result[key] ? 'text-text-primary' : 'text-text-muted italic'}`}>
                  {result[key] || 'Not found'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {!result && !loading && !error && (
        <div className="card text-center py-12">
          <Key className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted">Select a document to extract key commercial terms</p>
          <p className="text-xs text-text-muted mt-2">Extracts parties, dates, values, governing law, and more</p>
        </div>
      )}
    </div>
  );
}
