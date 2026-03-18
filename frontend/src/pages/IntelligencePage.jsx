import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Brain, Search, CheckCircle, AlertTriangle } from 'lucide-react';
import api from '../api/client';
import ConfidenceBadge from '../components/shared/ConfidenceBadge';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

const SUGGESTIONS = [
  'What is the termination notice period?',
  'Which contracts have uncapped liability?',
  'What are the indemnification obligations?',
  'Compare governing law across all agreements',
];

export default function IntelligencePage() {
  const [searchParams] = useSearchParams();
  const [query, setQuery] = useState('');
  const [jurisdiction, setJurisdiction] = useState('');
  const [year, setYear] = useState('');
  const [clauseType, setClauseType] = useState('');
  const [matterId, setMatterId] = useState(searchParams.get('matterId') || '');
  const [matters, setMatters] = useState([]);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/matters').then(r => setMatters(r.data)).catch(() => {});
  }, []);

  const handleQuery = async () => {
    if (!query.trim()) return;
    setLoading(true);
    setError('');
    setResult(null);
    try {
      const res = await api.post('/ai/query', {
        query, jurisdiction: jurisdiction || null,
        year: year ? parseInt(year) : null,
        clauseType: clauseType || null,
        matterId: matterId || null,
      });
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Query failed. Is the backend running?');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Intelligence</h1>

      <div className="card mb-6">
        <p className="text-text-muted text-sm mb-3">What would you like to know about your contracts?</p>
        <textarea
          value={query} onChange={e => setQuery(e.target.value)}
          placeholder="Ask about clauses, risks, obligations..."
          rows={3}
          className="input-field w-full resize-none mb-3"
          onKeyDown={e => e.key === 'Enter' && !e.shiftKey && (e.preventDefault(), handleQuery())}
        />
        <div className="flex items-center gap-3 flex-wrap">
          <select value={matterId} onChange={e => setMatterId(e.target.value)} className="input-field text-sm">
            <option value="">All Matters</option>
            {matters.map(m => (
              <option key={m.id} value={m.id}>{m.name} — {m.clientName}</option>
            ))}
          </select>
          <select value={jurisdiction} onChange={e => setJurisdiction(e.target.value)} className="input-field text-sm">
            <option value="">All Jurisdictions</option>
            {['Maharashtra', 'Delhi', 'Karnataka', 'Tamil Nadu', 'Gujarat', 'Rajasthan'].map(j => (
              <option key={j} value={j}>{j}</option>
            ))}
          </select>
          <input type="number" placeholder="Year" value={year} onChange={e => setYear(e.target.value)}
            className="input-field text-sm w-24" />
          <select value={clauseType} onChange={e => setClauseType(e.target.value)} className="input-field text-sm">
            <option value="">All Clauses</option>
            {['TERMINATION', 'LIABILITY', 'INDEMNITY', 'WARRANTY', 'CONFIDENTIALITY', 'GOVERNING_LAW'].map(c => (
              <option key={c} value={c}>{c.replace('_', ' ')}</option>
            ))}
          </select>
          <button onClick={handleQuery} disabled={loading || !query.trim()} className="btn-primary ml-auto flex items-center gap-2">
            {loading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> : <Search className="w-4 h-4" />}
            {loading ? 'Analyzing...' : 'Analyze'}
          </button>
        </div>
      </div>

      {!result && !loading && !error && (
        <div className="card text-center py-12">
          <Brain className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted mb-4">Try a query to analyze your contract corpus</p>
          <div className="flex flex-wrap gap-2 justify-center">
            {SUGGESTIONS.map(s => (
              <button key={s} onClick={() => setQuery(s)}
                className="text-xs bg-surface-el px-3 py-1.5 rounded-full text-text-secondary hover:text-primary hover:bg-primary/10 transition-colors">
                {s}
              </button>
            ))}
          </div>
        </div>
      )}

      {loading && <LoadingSkeleton rows={5} />}
      {error && <div className="card border-l-4 border-danger bg-danger/5"><p className="text-danger text-sm">{error}</p></div>}

      {result && (
        <div className="grid grid-cols-5 gap-6">
          <div className="col-span-3 card">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-semibold">AI Analysis</h2>
              <ConfidenceBadge level={result.confidence} />
            </div>
            <p className="text-text-secondary leading-relaxed whitespace-pre-wrap">{result.answer}</p>
            {result.keyClauses?.length > 0 && (
              <div className="mt-4 flex flex-wrap gap-2">
                {result.keyClauses.map(c => (
                  <span key={c} className="font-mono text-xs bg-primary/10 text-primary px-2 py-1 rounded">{c}</span>
                ))}
              </div>
            )}
            {result.warnings?.length > 0 && (
              <div className="mt-4 space-y-1">
                {result.warnings.map((w, i) => (
                  <div key={i} className="flex items-center gap-2 text-xs text-warning">
                    <AlertTriangle className="w-3 h-3" /> {w}
                  </div>
                ))}
              </div>
            )}
          </div>
          <div className="col-span-2 space-y-3">
            <h2 className="text-lg font-semibold mb-2">Sources</h2>
            {result.citations?.map((c, i) => (
              <div key={i} className="card !p-4">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium text-text-primary">{c.documentName}</span>
                  {c.verified
                    ? <CheckCircle className="w-4 h-4 text-success" />
                    : <AlertTriangle className="w-4 h-4 text-warning" />}
                </div>
                {c.sectionPath && <p className="font-mono text-xs text-primary mb-1">{c.sectionPath}</p>}
                {c.pageNumber && <p className="text-xs text-text-muted mb-2">Page {c.pageNumber}</p>}
                <p className="text-xs text-text-secondary bg-surface-el rounded p-2 leading-relaxed">{c.snippet}</p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
