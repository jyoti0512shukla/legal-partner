import { useState, useEffect } from 'react';
import { GitCompare, ArrowLeft, ArrowRight, Minus } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

export default function ComparePage() {
  const [docs, setDocs] = useState([]);
  const [doc1, setDoc1] = useState('');
  const [doc2, setDoc2] = useState('');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/documents?size=100').then(r => setDocs(r.data.content || [])).catch(() => {});
  }, []);

  const handleCompare = async () => {
    if (!doc1 || !doc2) return;
    setLoading(true); setError(''); setResult(null);
    try {
      const res = await api.post('/ai/compare', { documentId1: doc1, documentId2: doc2 });
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Comparison failed');
    } finally { setLoading(false); }
  };

  const getDocName = (id) => docs.find(d => d.id === id)?.fileName || 'Unknown';

  const FAVOR_ICON = {
    doc1: <ArrowLeft className="w-4 h-4 text-success" />,
    doc2: <ArrowRight className="w-4 h-4 text-primary" />,
    neutral: <Minus className="w-4 h-4 text-text-muted" />,
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Compare Contracts</h1>

      <div className="card mb-6">
        <p className="text-text-muted text-sm mb-4">Select two documents to compare across legal dimensions</p>
        <div className="flex items-end gap-4">
          <div className="flex-1">
            <label className="text-xs text-text-muted mb-1 block">Document 1</label>
            <select value={doc1} onChange={e => setDoc1(e.target.value)} className="input-field w-full text-sm">
              <option value="">Select document...</option>
              {docs.map(d => <option key={d.id} value={d.id}>{d.fileName} — {d.processingStatus || '?'}</option>)}
            </select>
          </div>
          <div className="flex-1">
            <label className="text-xs text-text-muted mb-1 block">Document 2</label>
            <select value={doc2} onChange={e => setDoc2(e.target.value)} className="input-field w-full text-sm">
              <option value="">Select document...</option>
              {docs.filter(d => d.id !== doc1).map(d => <option key={d.id} value={d.id}>{d.fileName} — {d.processingStatus || '?'}</option>)}
            </select>
          </div>
          <button onClick={handleCompare} disabled={loading || !doc1 || !doc2} className="btn-primary flex items-center gap-2">
            {loading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> : <GitCompare className="w-4 h-4" />}
            Compare
          </button>
        </div>
      </div>

      {error && <div className="card border-l-4 border-danger bg-danger/5 mb-4"><p className="text-danger text-sm">{error}</p></div>}
      {loading && <LoadingSkeleton rows={7} />}

      {result && (
        <div className="card overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-text-muted">
                <th className="pb-3 font-medium w-1/6">Dimension</th>
                <th className="pb-3 font-medium w-5/14">{getDocName(doc1)}</th>
                <th className="pb-3 font-medium w-5/14">{getDocName(doc2)}</th>
                <th className="pb-3 font-medium w-1/7 text-center">Favorable</th>
              </tr>
            </thead>
            <tbody>
              {result.dimensions?.map((dim, i) => (
                <tr key={i} className="border-b border-border/50">
                  <td className="py-4 font-medium text-text-primary">{dim.name}</td>
                  <td className="py-4 text-text-secondary pr-4">{dim.doc1Summary}</td>
                  <td className="py-4 text-text-secondary pr-4">{dim.doc2Summary}</td>
                  <td className="py-4 text-center">
                    <div className="flex items-center justify-center gap-1">
                      {FAVOR_ICON[dim.favorableTo] || FAVOR_ICON.neutral}
                      <span className="text-xs text-text-muted capitalize">{dim.favorableTo}</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!result && !loading && !error && (
        <div className="card text-center py-12">
          <GitCompare className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted">Select two documents above to see a detailed comparison</p>
        </div>
      )}
    </div>
  );
}
