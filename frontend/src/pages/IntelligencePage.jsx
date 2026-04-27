import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Brain, Send, FileText } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

const SUGGESTIONS = [
  'What is the termination notice period?',
  'Is liability capped? If so, to what amount?',
  'What are the indemnification obligations?',
  'Does this contract auto-renew?',
  'What are the payment terms?',
];

export default function IntelligencePage() {
  const [searchParams] = useSearchParams();
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState(searchParams.get('docId') || '');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [history, setHistory] = useState([]); // { question, answer }
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/documents?size=100&sort=uploadDate,desc')
      .then(r => setDocs(r.data.content || []))
      .catch(() => setDocs([]));
  }, []);

  // Clear Q&A when switching documents
  useEffect(() => {
    setAnswer(''); setHistory([]); setError('');
  }, [docId]);

  const ask = async () => {
    if (!docId || !question.trim()) return;
    const q = question.trim();
    setLoading(true); setError(''); setAnswer('');
    setQuestion('');

    try {
      // Try streaming first
      const response = await fetch(`/api/v1/ai/ask/${docId}/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ question: q }),
      });

      if (!response.ok) throw new Error('Stream failed');

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let accumulated = '';
      let fullResult = null;

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value, { stream: true });
        // Parse SSE events
        for (const line of text.split('\n')) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            // Check for event type from previous line
            if (fullResult === null && !data.startsWith('{')) {
              accumulated += data;
              setAnswer(accumulated);
            }
          }
          if (line.startsWith('event:complete')) {
            // Next data line is the full result JSON
            fullResult = 'pending';
          }
          if (fullResult === 'pending' && line.startsWith('data:')) {
            try {
              const result = JSON.parse(line.slice(5).trim());
              const a = result.answer || accumulated;
              setAnswer(a);
              setHistory(h => [{ question: q, answer: a }, ...h].slice(0, 10));
            } catch (e) { /* parse error, use accumulated */ }
            fullResult = 'done';
          }
        }
      }

      if (!fullResult) {
        // Stream ended without complete event — use accumulated text
        if (accumulated) {
          setHistory(h => [{ question: q, answer: accumulated }, ...h].slice(0, 10));
        }
      }
    } catch (e) {
      // Fallback to non-streaming
      try {
        const res = await api.post(`/ai/ask/${docId}`, { question: q });
        const a = res.data?.answer || 'No answer returned.';
        setAnswer(a);
        setHistory(h => [{ question: q, answer: a }, ...h].slice(0, 10));
      } catch (e2) {
        setError(e2.response?.data?.message || e2.message || 'Ask failed');
      }
    } finally {
      setLoading(false);
    }
  };

  const selectedDoc = docs.find(d => d.id === docId);

  return (
    <div>
      <h1 className="text-2xl font-bold mb-2">Ask Cognita</h1>
      <p className="text-text-muted text-sm mb-6">Select a contract, ask any question. Answers are grounded in that contract only.</p>

      <div className="card mb-6 space-y-4">
        <div>
          <label className="text-xs text-text-muted mb-1 block flex items-center gap-1">
            <FileText className="w-3 h-3" /> Contract
          </label>
          <select value={docId} onChange={e => setDocId(e.target.value)} className="input-field w-full text-sm">
            <option value="">Choose a contract…</option>
            {docs.map(d => (
              <option key={d.id} value={d.id}>
                {d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}
              </option>
            ))}
          </select>
        </div>

        {docId && (
          <>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Question</label>
              <textarea
                value={question} onChange={e => setQuestion(e.target.value)}
                placeholder="e.g. What is the termination notice period?"
                rows={2}
                className="input-field w-full resize-none"
                onKeyDown={e => e.key === 'Enter' && !e.shiftKey && (e.preventDefault(), ask())}
              />
            </div>
            <div className="flex items-center gap-2 flex-wrap">
              {SUGGESTIONS.map(s => (
                <button key={s} type="button" onClick={() => setQuestion(s)}
                  className="text-xs bg-surface-el px-3 py-1 rounded-full text-text-secondary hover:text-primary hover:bg-primary/10 transition-colors">
                  {s}
                </button>
              ))}
              <button onClick={ask} disabled={loading || !question.trim()}
                className="btn-primary ml-auto flex items-center gap-2 text-sm">
                {loading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                         : <Send className="w-4 h-4" />}
                {loading ? 'Thinking…' : 'Ask'}
              </button>
            </div>
          </>
        )}
      </div>

      {!docId && (
        <div className="card text-center py-12">
          <Brain className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted">Pick a contract above to start asking questions.</p>
        </div>
      )}

      {error && (
        <div className="card border-l-4 border-danger bg-danger/5 mb-4">
          <p className="text-danger text-sm">{error}</p>
        </div>
      )}

      {loading && !answer && <LoadingSkeleton rows={3} />}

      {answer && (
        <div className="card mb-4">
          <p className="text-xs text-text-muted mb-2">Latest answer · {selectedDoc?.fileName}</p>
          <p className="text-text-primary leading-relaxed whitespace-pre-wrap">{answer}</p>
        </div>
      )}

      {history.length > 1 && (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-text-muted">Previous questions</h3>
          {history.slice(1).map((h, i) => (
            <div key={i} className="card">
              <p className="text-xs font-medium text-text-secondary mb-2">Q: {h.question}</p>
              <p className="text-sm text-text-primary leading-relaxed whitespace-pre-wrap">{h.answer}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
