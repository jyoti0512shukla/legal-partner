import { useState, useEffect } from 'react';
import { X, Sparkles, Lock, Loader2, Plus, Trash2 } from 'lucide-react';
import api from '../../api/client';

export default function FinalizeModal({ docId, docName, onClose, onFinalized }) {
  const [brief, setBrief] = useState('');
  const [keyPoints, setKeyPoints] = useState([]);
  const [loading, setLoading] = useState(false);
  const [prefilling, setPrefilling] = useState(false);
  const [error, setError] = useState('');

  const prefill = async () => {
    setPrefilling(true);
    try {
      const res = await api.get(`/documents/${docId}/finalize/prefill`);
      if (res.data.suggestedBrief && !brief) setBrief(res.data.suggestedBrief);
      if (res.data.suggestedKeyPoints?.length && keyPoints.length === 0) {
        setKeyPoints(res.data.suggestedKeyPoints);
      }
    } catch { setError('Failed to load AI suggestions'); }
    finally { setPrefilling(false); }
  };

  const handleFinalize = async () => {
    if (!brief.trim()) { setError('Brief summary is required'); return; }
    setLoading(true);
    setError('');
    try {
      await api.post(`/documents/${docId}/finalize`, { userBrief: brief, keyPoints });
      onFinalized?.();
      onClose();
    } catch (e) {
      setError(e.response?.data?.message || 'Failed to finalize');
    } finally { setLoading(false); }
  };

  const addPoint = () => setKeyPoints([...keyPoints, '']);
  const removePoint = (i) => setKeyPoints(keyPoints.filter((_, idx) => idx !== i));
  const updatePoint = (i, v) => setKeyPoints(keyPoints.map((p, idx) => idx === i ? v : p));

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()} style={{ maxWidth: 520 }}>
        <div className="modal-header">
          <h3 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Lock size={16} /> Finalize Document
          </h3>
          <button className="icon-btn" onClick={onClose}><X size={16} /></button>
        </div>
        <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div className="card" style={{
            padding: 10, borderLeftWidth: 3, borderLeftColor: 'var(--warn-400)',
            background: 'var(--warn-bg)', fontSize: 12, color: 'var(--warn-400)'
          }}>
            This will lock <strong>{docName}</strong> for signature. No further edits or version uploads will be possible.
          </div>

          <div className="field">
            <label>Contract Brief</label>
            <textarea
              className="input"
              rows={4}
              placeholder="Brief summary of the contract (e.g., '3-year SaaS agreement with TechCorp, $240K annual, auto-renews')"
              value={brief}
              onChange={e => setBrief(e.target.value)}
              style={{ resize: 'vertical' }}
            />
            <button className="btn sm ghost" onClick={prefill} disabled={prefilling} style={{ marginTop: 4 }}>
              {prefilling ? <Loader2 size={12} className="animate-spin" /> : <Sparkles size={12} />}
              Pre-fill from AI
            </button>
          </div>

          <div className="field">
            <label>Key Points</label>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {keyPoints.map((p, i) => (
                <div key={i} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  <span style={{ color: 'var(--text-3)', fontSize: 11, width: 14 }}>•</span>
                  <input
                    className="input"
                    value={p}
                    onChange={e => updatePoint(i, e.target.value)}
                    placeholder="Key point..."
                    style={{ flex: 1 }}
                  />
                  <button className="icon-btn" onClick={() => removePoint(i)} style={{ width: 24, height: 24 }}>
                    <Trash2 size={12} />
                  </button>
                </div>
              ))}
              <button className="btn sm ghost" onClick={addPoint}>
                <Plus size={12} /> Add point
              </button>
            </div>
          </div>

          {error && <div className="small" style={{ color: 'var(--danger-400)' }}>{error}</div>}
        </div>
        <div className="modal-footer">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" onClick={handleFinalize} disabled={loading}>
            {loading ? <Loader2 size={14} className="animate-spin" /> : <Lock size={14} />}
            Lock & Finalize
          </button>
        </div>
      </div>
    </div>
  );
}
