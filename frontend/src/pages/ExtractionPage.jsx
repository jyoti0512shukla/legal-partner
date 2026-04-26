import { useState, useEffect } from 'react';
import { Key, Copy, CheckCircle, FileText } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

export default function ExtractionPage() {
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState('');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.get('/documents?size=100&sort=uploadDate,desc')
      .then(r => setDocs(r.data.content || []))
      .catch(() => {});
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

  // Build display fields from the result — show whatever the backend returns
  const fields = result ? Object.entries(result)
    .filter(([k]) => !['cached', 'generatedAt', 'documentType', 'contractType'].includes(k))
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => ({
      key: k,
      label: k.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').replace(/^\w/, c => c.toUpperCase()).trim(),
      value: String(v),
    })) : [];

  const notFound = result ? Object.entries(result)
    .filter(([k]) => !['cached', 'generatedAt', 'documentType', 'contractType'].includes(k))
    .filter(([, v]) => v === null || v === undefined || v === '' || v === 'N/A' || v === 'null')
    .map(([k]) => k.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').replace(/^\w/, c => c.toUpperCase()).trim())
    : [];

  const handleCopy = () => {
    if (!fields.length) return;
    const text = fields.map(f => `${f.label}: ${f.value}`).join('\n');
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const selectedDoc = docs.find(d => d.id === docId);
  const contractType = selectedDoc?.documentType || result?.contractType || result?.documentType;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-2">Key Terms Extraction</h1>
      <p className="text-text-muted text-sm mb-6">
        Extracts parties, dates, values, and clause-specific terms based on the detected contract type.
      </p>

      <div className="card mb-6">
        <div className="flex items-end gap-4">
          <div className="flex-1">
            <label className="text-xs text-text-muted mb-1 block">Select Document</label>
            <select value={docId} onChange={e => { setDocId(e.target.value); setResult(null); }} className="input-field w-full text-sm">
              <option value="">Choose a contract...</option>
              {docs.map(d => (
                <option key={d.id} value={d.id}>
                  {d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}{d.documentType ? ` [${d.documentType}]` : ''}
                </option>
              ))}
            </select>
          </div>
          <button onClick={handleExtract} disabled={loading || !docId} className="btn-primary flex items-center gap-2">
            {loading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> : <Key className="w-4 h-4" />}
            Extract Terms
          </button>
        </div>
      </div>

      {error && <div className="card border-l-4 border-danger bg-danger/5 mb-4"><p className="text-danger text-sm">{error}</p></div>}
      {loading && <LoadingSkeleton rows={10} />}

      {result && (
        <div className="card">
          <div className="flex items-center justify-between mb-5">
            <div>
              <h2 className="text-lg font-semibold">Extracted Key Terms</h2>
              {contractType && (
                <span className="text-xs text-text-muted">
                  Contract type: <span className="font-medium text-primary">{contractType}</span> — showing relevant fields
                </span>
              )}
            </div>
            <button onClick={handleCopy} className="flex items-center gap-1.5 text-sm text-text-muted hover:text-primary transition-colors">
              {copied ? <CheckCircle className="w-4 h-4 text-success" /> : <Copy className="w-4 h-4" />}
              {copied ? 'Copied' : 'Copy all'}
            </button>
          </div>
          <div className="grid grid-cols-1 divide-y divide-border">
            {fields.map(f => (
              <div key={f.key} className="flex items-start py-3 gap-4">
                <span className="text-sm text-text-muted w-48 shrink-0">{f.label}</span>
                <span className="text-sm font-medium text-text-primary">{f.value}</span>
              </div>
            ))}
          </div>
          {notFound.length > 0 && (
            <div className="mt-4 pt-4 border-t border-border">
              <span className="text-xs text-text-muted">Not found in document: </span>
              <span className="text-xs text-text-muted italic">{notFound.join(', ')}</span>
            </div>
          )}
        </div>
      )}

      {!result && !loading && !error && (
        <div className="card text-center py-12">
          <Key className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted">Select a document to extract key commercial terms</p>
          <p className="text-xs text-text-muted mt-2">Fields adapt based on contract type — NDA, SaaS, Employment, etc.</p>
        </div>
      )}
    </div>
  );
}
