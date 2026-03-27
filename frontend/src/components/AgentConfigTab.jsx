import { useState, useEffect } from 'react';
import { Loader2, Save, ExternalLink } from 'lucide-react';
import { Link } from 'react-router-dom';
import api from '../api/client';

const CHANNELS = ['IN_APP', 'SLACK', 'EMAIL', 'TEAMS', 'NONE'];

export default function AgentConfigTab() {
  const [config, setConfig] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    api.get('/agent/config')
      .then(r => setConfig(r.data))
      .catch(() => setConfig({
        autoAnalyzeOnUpload: true, crossReferenceDocs: true, checkPlaybook: true,
        notifyHigh: 'IN_APP', notifyMedium: 'IN_APP', notifyLow: 'NONE',
        quietHoursStart: null, quietHoursEnd: null,
      }))
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    setSaving(true);
    setSuccess(false);
    try {
      const res = await api.put('/agent/config', config);
      setConfig(res.data);
      setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
    } catch (e) {
      alert('Failed to save configuration');
    } finally {
      setSaving(false);
    }
  };

  const set = (key, val) => setConfig(prev => ({ ...prev, [key]: val }));

  if (loading || !config) {
    return (
      <div className="flex items-center gap-2 text-text-muted py-8">
        <Loader2 className="w-4 h-4 animate-spin" /> Loading agent configuration...
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-text-primary mb-1">AI Agent</h2>
        <p className="text-sm text-text-muted">
          Automatically analyzes documents when uploaded to a matter. Checks against playbooks and detects cross-document conflicts.
        </p>
      </div>

      {/* Triggers */}
      <section className="card p-5 space-y-4">
        <h3 className="text-sm font-semibold text-text-primary">Analysis Triggers</h3>

        <label className="flex items-center gap-3">
          <input type="checkbox" checked={config.autoAnalyzeOnUpload}
            onChange={e => set('autoAnalyzeOnUpload', e.target.checked)} className="w-4 h-4" />
          <div>
            <div className="text-sm text-text-primary">Auto-analyze on document upload</div>
            <div className="text-xs text-text-muted">When a document is linked to a matter, automatically run analysis</div>
          </div>
        </label>

        <label className="flex items-center gap-3">
          <input type="checkbox" checked={config.checkPlaybook}
            onChange={e => set('checkPlaybook', e.target.checked)} className="w-4 h-4" />
          <div>
            <div className="text-sm text-text-primary">Check against playbook</div>
            <div className="text-xs text-text-muted">Compare each clause against the matter's assigned playbook positions</div>
          </div>
        </label>

        <label className="flex items-center gap-3">
          <input type="checkbox" checked={config.crossReferenceDocs}
            onChange={e => set('crossReferenceDocs', e.target.checked)} className="w-4 h-4" />
          <div>
            <div className="text-sm text-text-primary">Cross-reference documents</div>
            <div className="text-xs text-text-muted">Compare extracted terms across all documents in the matter for conflicts</div>
          </div>
        </label>
      </section>

      {/* Notifications */}
      <section className="card p-5 space-y-4">
        <h3 className="text-sm font-semibold text-text-primary">Notification Channels</h3>

        <div className="grid grid-cols-3 gap-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">HIGH severity</label>
            <select value={config.notifyHigh} onChange={e => set('notifyHigh', e.target.value)}
              className="input-field w-full text-sm">
              {CHANNELS.filter(c => c !== 'NONE').map(c => <option key={c} value={c}>{c.replace('_', ' ')}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">MEDIUM severity</label>
            <select value={config.notifyMedium} onChange={e => set('notifyMedium', e.target.value)}
              className="input-field w-full text-sm">
              {CHANNELS.map(c => <option key={c} value={c}>{c.replace('_', ' ')}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">LOW severity</label>
            <select value={config.notifyLow} onChange={e => set('notifyLow', e.target.value)}
              className="input-field w-full text-sm">
              {CHANNELS.map(c => <option key={c} value={c}>{c.replace('_', ' ')}</option>)}
            </select>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">Quiet hours start</label>
            <input type="time" value={config.quietHoursStart || ''}
              onChange={e => set('quietHoursStart', e.target.value || null)}
              className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Quiet hours end</label>
            <input type="time" value={config.quietHoursEnd || ''}
              onChange={e => set('quietHoursEnd', e.target.value || null)}
              className="input-field w-full text-sm" />
          </div>
        </div>
      </section>

      {/* Actions */}
      <div className="flex items-center gap-3">
        <button onClick={handleSave} disabled={saving}
          className="btn-primary flex items-center gap-2 text-sm">
          {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
          {saving ? 'Saving...' : success ? 'Saved!' : 'Save Configuration'}
        </button>

        <Link to="/playbooks" className="btn-secondary flex items-center gap-2 text-sm">
          <ExternalLink className="w-4 h-4" /> Manage Playbooks
        </Link>
      </div>
    </div>
  );
}
