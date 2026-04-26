import { useState } from 'react';
import { X, Plus, Trash2, Send, Loader2, CheckCircle2, GripVertical } from 'lucide-react';
import api from '../api/client';

const ROLES = [
  { value: 'SIGNER', label: 'Signer', desc: 'Signs the document' },
  { value: 'REVIEWER', label: 'Reviewer', desc: 'Reviews & approves' },
  { value: 'CC', label: 'CC', desc: 'Gets a copy' },
];

export default function SendForSignatureModal({ docId, docName, matterId, parties, onClose, onSent }) {
  const [recipients, setRecipients] = useState(() => {
    // Pre-populate from matter parties if available
    const initial = [];
    if (parties?.partyA) {
      initial.push({ email: parties.partyAEmail || '', name: parties.partyA, role: 'SIGNER', routingOrder: 1 });
    }
    if (parties?.partyB) {
      initial.push({ email: parties.partyBEmail || '', name: parties.partyB, role: 'SIGNER', routingOrder: 2 });
    }
    if (initial.length === 0) {
      initial.push({ email: '', name: '', role: 'SIGNER', routingOrder: 1 });
    }
    return initial;
  });
  const [subject, setSubject] = useState(`Please sign: ${docName || 'Contract'}`);
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(null);
  const [error, setError] = useState('');

  const addRecipient = () => {
    const maxOrder = Math.max(...recipients.map(r => r.routingOrder), 0);
    setRecipients(prev => [...prev, { email: '', name: '', role: 'SIGNER', routingOrder: maxOrder + 1 }]);
  };

  const removeRecipient = (i) => setRecipients(prev => prev.filter((_, idx) => idx !== i));

  const updateRecipient = (i, field, value) => {
    setRecipients(prev => prev.map((r, idx) => idx === i ? { ...r, [field]: value } : r));
  };

  const handleSend = async () => {
    const valid = recipients.filter(r => r.email.trim() && r.name.trim());
    if (valid.length === 0) { setError('Add at least one recipient with email and name'); return; }
    const invalidEmail = valid.find(r => !r.email.includes('@'));
    if (invalidEmail) { setError(`Invalid email: ${invalidEmail.email}`); return; }

    setSending(true); setError('');
    try {
      const res = await api.post(`/integrations/docusign/send/${docId}`, {
        recipients: valid.map((r, i) => ({ ...r, routingOrder: r.routingOrder || i + 1 })),
        emailSubject: subject,
        matterId: matterId || null,
      });
      setSent(res.data);
      if (onSent) onSent(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Failed to send for signature');
    } finally { setSending(false); }
  };

  if (sent) {
    return (
      <div className="modal-overlay" onClick={onClose}>
        <div className="modal-content" onClick={e => e.stopPropagation()} style={{ maxWidth: 480 }}>
          <div style={{ textAlign: 'center', padding: '32px 24px' }}>
            <CheckCircle2 size={48} style={{ color: 'var(--success-400)', marginBottom: 16 }} />
            <h3 style={{ fontSize: 18, fontWeight: 600, marginBottom: 8 }}>Sent for Signature</h3>
            <p className="small muted" style={{ marginBottom: 4 }}>
              Envelope sent to {sent.recipientCount} recipient{sent.recipientCount !== 1 ? 's' : ''}
            </p>
            <p className="tiny muted" style={{ marginBottom: 24 }}>
              DocuSign will email each recipient in order. You'll be notified when all parties have signed.
            </p>
            <button className="btn primary" onClick={onClose}>Done</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={e => e.stopPropagation()} style={{ maxWidth: 560 }}>
        <div className="modal-header">
          <h3 style={{ fontSize: 16, fontWeight: 600 }}>Send for Signature</h3>
          <button className="icon-btn" onClick={onClose}><X size={16} /></button>
        </div>

        <div style={{ padding: '16px 20px' }}>
          {/* Email subject */}
          <div style={{ marginBottom: 16 }}>
            <label className="tiny muted" style={{ display: 'block', marginBottom: 4 }}>Email Subject</label>
            <input value={subject} onChange={e => setSubject(e.target.value)}
                   className="input-field" style={{ width: '100%', fontSize: 13 }} />
          </div>

          {/* Recipients */}
          <div style={{ marginBottom: 12 }}>
            <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
              <span className="tiny" style={{ fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-3)' }}>
                Recipients ({recipients.length})
              </span>
              <button className="btn ghost sm" onClick={addRecipient}>
                <Plus size={12} /> Add
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {recipients.map((r, i) => (
                <div key={i} style={{
                  display: 'grid', gridTemplateColumns: '24px 1fr 1fr 110px 28px', gap: 8, alignItems: 'center',
                  padding: '8px 10px', background: 'var(--bg-2)', borderRadius: 'var(--r-md)', border: '1px solid var(--line-1)',
                }}>
                  <span className="tiny muted" style={{ textAlign: 'center', fontWeight: 600 }}>{r.routingOrder}</span>
                  <input value={r.name} onChange={e => updateRecipient(i, 'name', e.target.value)}
                         placeholder="Name" className="input-field" style={{ fontSize: 12, padding: '6px 8px' }} />
                  <input value={r.email} onChange={e => updateRecipient(i, 'email', e.target.value)}
                         placeholder="email@example.com" className="input-field" style={{ fontSize: 12, padding: '6px 8px' }} />
                  <select value={r.role} onChange={e => updateRecipient(i, 'role', e.target.value)}
                          className="input-field" style={{ fontSize: 11, padding: '6px 4px' }}>
                    {ROLES.map(role => (
                      <option key={role.value} value={role.value}>{role.label}</option>
                    ))}
                  </select>
                  {recipients.length > 1 && (
                    <button className="icon-btn" onClick={() => removeRecipient(i)} style={{ width: 24, height: 24 }}>
                      <Trash2 size={12} style={{ color: 'var(--danger-400)' }} />
                    </button>
                  )}
                </div>
              ))}
            </div>

            <div className="tiny muted" style={{ marginTop: 8 }}>
              Recipients are emailed in order. Signer 1 signs first, then Signer 2, etc. CC recipients get the final signed copy.
            </div>
          </div>

          {error && (
            <div style={{ padding: '8px 12px', background: 'var(--danger-bg)', borderRadius: 'var(--r-md)', marginBottom: 12 }}>
              <span className="small" style={{ color: 'var(--danger-400)' }}>{error}</span>
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" onClick={handleSend} disabled={sending}>
            {sending ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
            {sending ? 'Sending...' : 'Send for Signature'}
          </button>
        </div>
      </div>
    </div>
  );
}
