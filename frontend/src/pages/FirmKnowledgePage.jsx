import { useState, useEffect, useCallback } from 'react';
import { GraduationCap, Check, X, Archive, RefreshCw, Loader2, Plus, ChevronDown, ChevronUp } from 'lucide-react';
import api from '../api/client';

/*
 * Firm Knowledge — what the system has learned from this firm's contracts, and the lawyer
 * gate on it. Statuses, positions and permissions come from /learning/settings.
 */

const pct = (v) => (v == null || Number.isNaN(v) ? '—' : `${Math.round(v * 100)}%`);
const label = (s) => (s || '').replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase());

function Tabs({ tab, setTab }) {
  const tabs = [['overview', 'Overview'], ['clauses', 'Firm clauses'], ['insights', 'Learned preferences']];
  return (
    <div style={{ display: 'flex', gap: 6, marginBottom: 16 }}>
      {tabs.map(([k, l]) => (
        <button key={k} type="button" onClick={() => setTab(k)}
          className={tab === k ? 'btn-primary text-sm' : 'btn-secondary text-sm'}>{l}</button>
      ))}
    </div>
  );
}

function Stat({ title, value, hint }) {
  return (
    <div className="card" style={{ padding: 14 }}>
      <div className="tiny muted">{title}</div>
      <div style={{ fontSize: 22, fontWeight: 600, marginTop: 4 }}>{value}</div>
      {hint && <div className="tiny muted" style={{ marginTop: 4 }}>{hint}</div>}
    </div>
  );
}

function EditTable({ title, stats, hint }) {
  const rows = Object.entries(stats || {});
  return (
    <div className="card" style={{ padding: 14 }}>
      <div style={{ fontWeight: 600, marginBottom: 4 }}>{title}</div>
      {hint && <div className="tiny muted" style={{ marginBottom: 8 }}>{hint}</div>}
      {rows.length === 0 ? <div className="tiny muted">No edited drafts yet.</div> : (
        <table className="w-full text-sm">
          <thead><tr className="tiny muted"><th className="text-left">Source</th><th className="text-right">Clauses</th><th className="text-right">Avg. edited</th></tr></thead>
          <tbody>
            {rows.map(([k, v]) => (
              <tr key={k}><td>{label(k)}</td><td className="text-right">{v.n}</td><td className="text-right">{pct(v.meanEditRatio)}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function Overview({ metrics, onReload }) {
  if (!metrics) return <div className="muted small">Loading…</div>;
  const m = metrics;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="tiny muted">Last {m.windowDays} days. <button type="button" onClick={onReload} className="tiny" style={{ background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', color: 'inherit' }}>Refresh</button></div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
        <Stat title="Firm clauses awaiting review" value={m.firmClauses?.CANDIDATE ?? 0} hint={`${m.firmClauses?.APPROVED ?? 0} approved`} />
        <Stat title="Active learned preferences" value={m.insights?.ACTIVE ?? 0} hint={`${m.insights?.PROPOSED ?? 0} proposed`} />
        <Stat title="Review answers lawyers agreed with" value={pct(m.reviewAgreement)} hint={`${m.reviewDisputes} corrections / ${m.reviewAnswers} answers`} />
        <Stat title="Drafted clauses" value={Object.values(m.draftedClausesBySource || {}).reduce((a, b) => a + b, 0)}
          hint={Object.entries(m.draftedClausesBySource || {}).map(([k, v]) => `${label(k)} ${v}`).join(' · ')} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 12 }}>
        <EditTable title="How much lawyers edit drafted clauses" stats={m.editsBySource}
          hint="Lower is better. Firm clauses and learned preferences should need less editing than plain AI text." />
        <EditTable title="Signed contracts only" stats={m.signedEditsBySource} hint="Edits in the version that was signed." />
        <EditTable title="AI clauses with vs without learned preferences" stats={m.llmEditsByInsightUse} />
      </div>
      {m.questionsNeedingRewrite?.length > 0 && (
        <div className="card" style={{ padding: 14 }}>
          <div style={{ fontWeight: 600, marginBottom: 6 }}>Review questions lawyers keep correcting</div>
          <div className="tiny muted" style={{ marginBottom: 8 }}>These questions are down-weighted automatically; consider rewording them in the risk question set.</div>
          {m.questionsNeedingRewrite.map(q => (
            <div key={q.questionId} className="small">{q.questionId} — {pct(q.precision)} correct over {q.answered} answers</div>
          ))}
        </div>
      )}
    </div>
  );
}

function FirmClauseCard({ fc, positions, onChanged }) {
  const [open, setOpen] = useState(fc.status === 'CANDIDATE');
  const [text, setText] = useState(fc.text);
  const [position, setPosition] = useState(fc.position || positions[0]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const act = async (path, body) => {
    setBusy(true); setError('');
    try { await api.post(`/learning/firm-clauses/${fc.id}/${path}`, body); onChanged(); }
    catch (err) { setError(err.response?.data?.message || 'Action failed'); }
    finally { setBusy(false); }
  };

  return (
    <div className="card" style={{ padding: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }} onClick={() => setOpen(!open)}>
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600 }}>{label(fc.clauseKey)} <span className="tiny muted">· {fc.contractType}</span></div>
          <div className="tiny muted">
            Used in {fc.supportCount} contracts{fc.executedCount ? ` (${fc.executedCount} signed)` : ''} · {label(fc.status)}
            {fc.status === 'APPROVED' && ` · ${label(fc.position)} · drafted ${fc.timesUsed}×`}
          </div>
        </div>
        {open ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
      </div>
      {open && (
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <textarea className="input-field w-full text-sm font-mono" rows={8} value={text}
            onChange={e => setText(e.target.value)} disabled={fc.status !== 'CANDIDATE' && fc.status !== 'APPROVED'} />
          <div className="tiny muted">{'{{partyA.name}}'} / {'{{partyB.name}}'} become this deal&apos;s parties; other {'{{…}}'} fields are left for the lawyer to fill.</div>
          {error && <div className="tiny" style={{ color: 'var(--danger-400)' }}>{error}</div>}
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            {(fc.status === 'CANDIDATE' || fc.status === 'APPROVED') && (
              <>
                <select className="input-field text-sm" value={position} onChange={e => setPosition(e.target.value)}>
                  {positions.map(p => <option key={p} value={p}>{label(p)}</option>)}
                </select>
                <button type="button" className="btn-primary text-sm flex items-center gap-1" disabled={busy}
                  onClick={() => act('approve', { text: text !== fc.text ? text : null, position })}>
                  <Check size={14} /> {fc.status === 'APPROVED' ? 'Save' : 'Approve'}
                </button>
              </>
            )}
            {fc.status === 'CANDIDATE' && (
              <button type="button" className="btn-secondary text-sm flex items-center gap-1" disabled={busy} onClick={() => act('reject')}>
                <X size={14} /> Reject
              </button>
            )}
            {fc.status === 'APPROVED' && (
              <button type="button" className="btn-secondary text-sm flex items-center gap-1" disabled={busy} onClick={() => act('retire')}>
                <Archive size={14} /> Retire
              </button>
            )}
            {busy && <Loader2 size={14} className="animate-spin" />}
          </div>
        </div>
      )}
    </div>
  );
}

function FirmClauses({ settings }) {
  const [status, setStatus] = useState('CANDIDATE');
  const [rows, setRows] = useState(null);
  const [mining, setMining] = useState(false);
  const [report, setReport] = useState(null);

  const load = useCallback(() => {
    setRows(null);
    api.get('/learning/firm-clauses', { params: { status } }).then(r => setRows(r.data)).catch(() => setRows([]));
  }, [status]);
  useEffect(load, [load]);

  const mine = async () => {
    setMining(true);
    try { const r = await api.post('/learning/mine'); setReport(r.data); load(); }
    finally { setMining(false); }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className="small muted">
        Wording that recurs across your firm&apos;s contracts. Once approved, the drafter uses it before generic text.
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <select className="input-field text-sm" value={status} onChange={e => setStatus(e.target.value)}>
          {settings.firmClauseStatuses.map(s => <option key={s} value={s}>{label(s)}</option>)}
        </select>
        <button type="button" className="btn-secondary text-sm flex items-center gap-1" onClick={mine} disabled={mining}>
          {mining ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />} Find recurring clauses now
        </button>
        {report && <span className="tiny muted">{report.created} new, {report.updated} updated</span>}
      </div>
      {rows == null ? <div className="muted small">Loading…</div>
        : rows.length === 0 ? <div className="muted small">Nothing here yet. Clauses appear after the same wording is found in several of your contracts.</div>
        : rows.map(fc => <FirmClauseCard key={fc.id} fc={fc} positions={settings.positions} onChanged={load} />)}
    </div>
  );
}

function Insights({ settings }) {
  const [status, setStatus] = useState('PROPOSED');
  const [rows, setRows] = useState(null);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ clauseKey: '', contractType: '', content: '' });
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setRows(null);
    api.get('/learning/insights', { params: { status } }).then(r => setRows(r.data)).catch(() => setRows([]));
  }, [status]);
  useEffect(load, [load]);

  const setInsightStatus = async (id, s) => { await api.post(`/learning/insights/${id}/status`, { status: s }); load(); };
  const add = async (e) => {
    e.preventDefault(); setError('');
    try { await api.post('/learning/insights', form); setAdding(false); setForm({ clauseKey: '', contractType: '', content: '' }); load(); }
    catch (err) { setError(err.response?.data?.message || 'Could not save'); }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className="small muted">
        Drafting rules inferred from how your lawyers edit AI drafts. Active rules are added to the drafting prompt for that clause.
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <select className="input-field text-sm" value={status} onChange={e => setStatus(e.target.value)}>
          {settings.insightStatuses.map(s => <option key={s} value={s}>{label(s)}</option>)}
        </select>
        <button type="button" className="btn-secondary text-sm flex items-center gap-1" onClick={() => setAdding(!adding)}>
          <Plus size={14} /> Add a rule
        </button>
      </div>
      {adding && (
        <form onSubmit={add} className="card" style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            <input className="input-field text-sm" placeholder="Clause (e.g. LIABILITY)" value={form.clauseKey}
              onChange={e => setForm({ ...form, clauseKey: e.target.value })} required />
            <input className="input-field text-sm" placeholder="Contract type (optional, e.g. MSA)" value={form.contractType}
              onChange={e => setForm({ ...form, contractType: e.target.value })} />
          </div>
          <textarea className="input-field text-sm" rows={2} placeholder="e.g. Always carve out data protection breaches from the liability cap"
            value={form.content} onChange={e => setForm({ ...form, content: e.target.value })} required maxLength={300} />
          {error && <div className="tiny" style={{ color: 'var(--danger-400)' }}>{error}</div>}
          <div><button type="submit" className="btn-primary text-sm">Save as active rule</button></div>
        </form>
      )}
      {rows == null ? <div className="muted small">Loading…</div>
        : rows.length === 0 ? <div className="muted small">No rules with this status.</div>
        : rows.map(i => (
          <div key={i.id} className="card" style={{ padding: 14, display: 'flex', gap: 12, alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              <div className="small" style={{ fontWeight: 500 }}>{i.content}</div>
              <div className="tiny muted" style={{ marginTop: 4 }}>
                {label(i.clauseKey)}{i.contractType ? ` · ${i.contractType}` : ' · all contract types'} · seen in {i.evidenceCount} edit(s)
                {(i.helpfulCount > 0 || i.harmfulCount > 0) && ` · signed with little editing ${i.helpfulCount}×, heavily edited ${i.harmfulCount}×`}
                {i.source === 'MANUAL' && ' · added manually'}
              </div>
            </div>
            <div style={{ display: 'flex', gap: 6 }}>
              {i.status !== 'ACTIVE' && <button type="button" className="btn-primary text-sm" onClick={() => setInsightStatus(i.id, 'ACTIVE')}>Activate</button>}
              {i.status !== 'RETIRED' && <button type="button" className="btn-secondary text-sm" onClick={() => setInsightStatus(i.id, 'RETIRED')}>Retire</button>}
            </div>
          </div>
        ))}
    </div>
  );
}

export default function FirmKnowledgePage() {
  const [settings, setSettings] = useState(null);
  const [metrics, setMetrics] = useState(null);
  const [tab, setTab] = useState('overview');

  const loadMetrics = useCallback(() => {
    api.get('/learning/metrics').then(r => setMetrics(r.data)).catch(() => setMetrics(null));
  }, []);

  useEffect(() => {
    api.get('/learning/settings').then(r => {
      setSettings(r.data);
      if (r.data?.canCurate) loadMetrics();
    }).catch(() => setSettings({ canCurate: false }));
  }, [loadMetrics]);

  if (!settings) return <div className="p-6 muted small">Loading…</div>;

  return (
    <div className="p-6" style={{ maxWidth: 1100 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
        <GraduationCap size={20} />
        <h1 className="text-xl font-semibold">Firm Knowledge</h1>
      </div>
      <p className="small muted" style={{ marginBottom: 16 }}>
        What the assistant has learned from your firm&apos;s contracts and edits. Nothing learned is used until it is approved here
        (or, for preferences, seen in several separate edits).
      </p>
      {!settings.enabled && <div className="card small" style={{ padding: 12, marginBottom: 12 }}>Learning is switched off for this deployment.</div>}
      {!settings.canCurate ? (
        <div className="card small" style={{ padding: 14 }}>You don&apos;t have permission to review firm knowledge.</div>
      ) : (
        <>
          <Tabs tab={tab} setTab={setTab} />
          {tab === 'overview' && <Overview metrics={metrics} onReload={loadMetrics} />}
          {tab === 'clauses' && <FirmClauses settings={settings} />}
          {tab === 'insights' && <Insights settings={settings} />}
        </>
      )}
    </div>
  );
}
