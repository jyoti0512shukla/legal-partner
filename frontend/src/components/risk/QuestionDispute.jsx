import { useState } from 'react';
import { Loader2 } from 'lucide-react';
import api from '../../api/client';

/**
 * "Mark wrong" on one review answer. The correction lowers the question's weight in this
 * firm's future risk scores and is shown to the model as an example next time.
 */
export default function QuestionDispute({ docId, clauseType, q }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const [state, setState] = useState('idle'); // idle | saving | done | error
  const [error, setError] = useState('');

  if (q.answer !== 'YES' && q.answer !== 'NO') return null;
  const correct = q.answer === 'YES' ? 'NO' : 'YES';

  const submit = async () => {
    setState('saving'); setError('');
    try {
      await api.post(`/ai/risk-assessment/${docId}/disputes`, {
        questionId: q.id, clauseType, modelAnswer: q.answer, correctAnswer: correct,
        quote: q.quote || q.quotedEvidence || null, note: note.trim() || null,
      });
      setState('done');
    } catch (err) {
      setError(err.response?.data?.message || 'Could not record the correction');
      setState('error');
    }
  };

  if (state === 'done') {
    return <div className="tiny" style={{ marginTop: 6, color: 'var(--success-400)' }}>Correction recorded — thanks, future reviews will use it.</div>;
  }
  if (!open) {
    return (
      <button type="button" className="tiny" onClick={() => setOpen(true)}
        style={{ marginTop: 6, background: 'none', border: 'none', padding: 0, color: 'var(--text-3)', cursor: 'pointer', textDecoration: 'underline' }}>
        Mark wrong
      </button>
    );
  }
  return (
    <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div className="tiny muted">
        Correct answer: <strong>{correct === 'YES' ? 'Present' : 'Absent'}</strong>
      </div>
      <input className="input-field text-sm" value={note} onChange={e => setNote(e.target.value)}
        placeholder="Optional: where is it, or why is the answer wrong?" maxLength={500} />
      {error && <div className="tiny" style={{ color: 'var(--danger-400)' }}>{error}</div>}
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" className="btn-primary text-sm" disabled={state === 'saving'} onClick={submit}>
          {state === 'saving' ? <Loader2 size={12} className="animate-spin" /> : 'Record correction'}
        </button>
        <button type="button" className="btn-secondary text-sm" onClick={() => setOpen(false)}>Cancel</button>
      </div>
    </div>
  );
}
