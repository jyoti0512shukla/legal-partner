import { useState, useEffect } from 'react';
import { Send, Trash2, Loader2 } from 'lucide-react';
import api from '../../api/client';

export default function NotesSection({ docId }) {
  const [notes, setNotes] = useState([]);
  const [newNote, setNewNote] = useState('');
  const [saving, setSaving] = useState(false);

  const load = () => {
    api.get(`/documents/${docId}/notes`).then(r => setNotes(r.data || [])).catch(() => {});
  };
  useEffect(() => { if (docId) load(); }, [docId]);

  const handleAdd = async () => {
    if (!newNote.trim()) return;
    setSaving(true);
    try {
      await api.post(`/documents/${docId}/notes`, { content: newNote.trim() });
      setNewNote('');
      load();
    } catch {}
    finally { setSaving(false); }
  };

  const handleDelete = async (noteId) => {
    try { await api.delete(`/documents/notes/${noteId}`); load(); } catch {}
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleAdd(); }
  };

  return (
    <div>
      <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-2)', marginBottom: 8 }}>Notes</div>
      <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
        <textarea
          className="input"
          value={newNote}
          onChange={e => setNewNote(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Add a note..."
          rows={2}
          style={{ flex: 1, resize: 'vertical', fontSize: 12 }}
        />
        <button className="btn sm" onClick={handleAdd} disabled={saving || !newNote.trim()}
          style={{ alignSelf: 'flex-end' }}>
          {saving ? <Loader2 size={12} className="animate-spin" /> : <Send size={12} />}
        </button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {notes.map(n => (
          <div key={n.id} style={{
            padding: '8px 10px', background: 'var(--bg-2)', borderRadius: 'var(--r-sm)',
            border: '1px solid var(--line-1)',
          }}>
            <div style={{ fontSize: 12, color: 'var(--text-1)', whiteSpace: 'pre-wrap' }}>{n.content}</div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
              <span style={{ fontSize: 10, color: 'var(--text-3)' }}>
                {n.createdBy} · {new Date(n.createdAt).toLocaleString()}
              </span>
              <button className="icon-btn" onClick={() => handleDelete(n.id)}
                title="Delete note" style={{ width: 20, height: 20, opacity: 0.5 }}>
                <Trash2 size={10} />
              </button>
            </div>
          </div>
        ))}
        {notes.length === 0 && <div className="small muted">No notes yet</div>}
      </div>
    </div>
  );
}
