import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import {
  Loader2, ArrowRight, ArrowUp, ArrowDown, CheckCircle2, Clock, AlertTriangle, Users,
  Brain, Shield, GitPullRequest, FileText, Wand2, Upload, Briefcase,
} from 'lucide-react';
import api from '../api/client';

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [reviewData, setReviewData] = useState(null);
  const [aiData, setAiData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/review-pipelines/dashboard').catch(() => ({ data: { needsAction: [], teamActivity: [], recentlyCompleted: [] } })),
      api.get('/findings/dashboard').catch(() => ({ data: { highCount: 0, mediumCount: 0, lowCount: 0, unreviewedCount: 0, totalCount: 0, recentFindings: [], matterRiskSummary: [] } })),
    ]).then(([reviewRes, aiRes]) => {
      setReviewData(reviewRes.data);
      setAiData(aiRes.data);
    }).finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <div className="page" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '60vh' }}>
      <Loader2 className="animate-spin" size={24} style={{ color: 'var(--text-3)' }} />
    </div>
  );

  const { needsAction = [], teamActivity = [], recentlyCompleted = [] } = reviewData || {};
  const { highCount = 0, mediumCount = 0, lowCount = 0, unreviewedCount = 0, totalCount = 0, recentFindings = [], matterRiskSummary = [] } = aiData || {};

  const actionItems = [];
  if (unreviewedCount > 0) actionItems.push(`${unreviewedCount} unreviewed finding${unreviewedCount > 1 ? 's' : ''}`);
  if (needsAction.length > 0) actionItems.push(`${needsAction.length} review${needsAction.length > 1 ? 's' : ''} awaiting action`);

  const displayName = user?.displayName || user?.email?.split('@')[0];

  return (
    <div className="page">
      {/* Greeting */}
      <div className="page-header">
        <div>
          <h1 className="page-title">{getGreeting()}, {displayName}.</h1>
          <div className="page-sub">
            {actionItems.length > 0
              ? actionItems.join(' and ') + ' need your attention.'
              : 'All caught up. No items need your attention right now.'}
          </div>
        </div>
        <div className="row">
          <button className="btn" onClick={() => navigate('/documents')}>
            <Upload size={14} /> Upload
          </button>
          <button className="btn primary" onClick={() => navigate('/draft')}>
            <Wand2 size={14} /> New draft
          </button>
        </div>
      </div>

      {/* Stats cards */}
      <div className="grid-4" style={{ marginBottom: 18 }}>
        <div className="stat">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">HIGH Risk Findings</span>
            <AlertTriangle size={14} style={{ color: 'var(--text-4)' }} />
          </div>
          <div className="value" style={{ color: highCount > 0 ? 'var(--danger-400)' : undefined }}>{highCount}</div>
          {unreviewedCount > 0 && (
            <div className="delta down"><ArrowUp size={11} /> {unreviewedCount} unreviewed</div>
          )}
        </div>
        <div className="stat">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">MEDIUM Risk</span>
            <Shield size={14} style={{ color: 'var(--text-4)' }} />
          </div>
          <div className="value" style={{ color: mediumCount > 0 ? 'var(--warn-400)' : undefined }}>{mediumCount}</div>
        </div>
        <div className="stat">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">Pending Reviews</span>
            <GitPullRequest size={14} style={{ color: 'var(--text-4)' }} />
          </div>
          <div className="value">{needsAction.length}</div>
          {needsAction.length > 0 && (
            <div className="delta down"><ArrowDown size={11} /> Awaiting action</div>
          )}
        </div>
        <div className="stat">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">Total Findings</span>
            <Brain size={14} style={{ color: 'var(--text-4)' }} />
          </div>
          <div className="value">{totalCount}</div>
          <div className="delta">
            <span style={{ color: 'var(--success-400)' }}>{lowCount} low</span>
          </div>
        </div>
      </div>

      {/* Main content: matters at risk + activity */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: 18 }}>
        {/* Matters needing attention */}
        <div className="card">
          <div className="card-header">
            <AlertTriangle size={14} style={{ color: 'var(--warn-400)' }} />
            <h3>Matters Needing Attention</h3>
            <span className="sub">{matterRiskSummary.length} matters</span>
            <button className="btn ghost sm" style={{ marginLeft: 'auto' }} onClick={() => navigate('/matters')}>
              View all <ArrowRight size={12} />
            </button>
          </div>
          {matterRiskSummary.length > 0 ? (
            <table className="table">
              <thead>
                <tr><th>Matter</th><th>High</th><th>Medium</th><th>Low</th></tr>
              </thead>
              <tbody>
                {matterRiskSummary.map(m => (
                  <tr key={m.matterId} onClick={() => navigate(`/matters/${m.matterId}`)}>
                    <td style={{ fontWeight: 500 }}>{m.matterName}</td>
                    <td>{m.high > 0 ? <span className="badge high">{m.high}</span> : <span className="muted">0</span>}</td>
                    <td>{m.medium > 0 ? <span className="badge med">{m.medium}</span> : <span className="muted">0</span>}</td>
                    <td>{m.low > 0 ? <span className="badge low">{m.low}</span> : <span className="muted">0</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-3)', fontSize: 13 }}>
              No matters with risk findings yet.
            </div>
          )}
        </div>

        {/* Recent findings feed */}
        <div className="card">
          <div className="card-header">
            <Brain size={14} style={{ color: 'var(--brand-400)' }} />
            <h3>Recent Findings</h3>
            <span className="sub">{recentFindings.length}</span>
          </div>
          <div style={{ padding: 4 }}>
            {recentFindings.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-3)', fontSize: 13 }}>
                No AI findings yet. Upload documents and run analysis.
              </div>
            ) : (
              recentFindings.map((f, i) => (
                <div
                  key={f.id || i}
                  onClick={() => navigate(`/matters/${f.matterId}`)}
                  style={{
                    display: 'flex', gap: 10, padding: '10px 12px', cursor: 'pointer',
                    borderBottom: i < recentFindings.length - 1 ? '1px solid var(--line-1)' : 'none',
                  }}
                >
                  <div style={{ marginTop: 2 }}>
                    {f.severity === 'HIGH' && <span style={{ display: 'inline-grid', placeItems: 'center', width: 22, height: 22, borderRadius: '50%', background: 'var(--danger-bg)', color: 'var(--danger-400)', fontSize: 10, fontWeight: 700 }}>!</span>}
                    {f.severity === 'MEDIUM' && <span style={{ display: 'inline-grid', placeItems: 'center', width: 22, height: 22, borderRadius: '50%', background: 'var(--warn-bg)', color: 'var(--warn-400)', fontSize: 10, fontWeight: 700 }}>!</span>}
                    {f.severity === 'LOW' && <span style={{ display: 'inline-grid', placeItems: 'center', width: 22, height: 22, borderRadius: '50%', background: 'var(--success-bg)', color: 'var(--success-400)', fontSize: 10, fontWeight: 700 }}>i</span>}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="small" style={{ fontWeight: 500 }}>{f.title}</div>
                    <div className="tiny muted" style={{ marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.description}</div>
                    <div className="tiny muted" style={{ marginTop: 4 }}>
                      {f.documentName && <span>{f.documentName}</span>}
                      {f.clauseType && <span> &middot; {f.clauseType.replace(/_/g, ' ')}</span>}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* Quick actions */}
      <div style={{ marginTop: 18 }} className="grid-3">
        <div className="card" style={{ padding: 18, cursor: 'pointer' }} onClick={() => navigate('/draft')}>
          <div style={{
            width: 36, height: 36, borderRadius: 8,
            background: 'linear-gradient(135deg, var(--brand-500), var(--teal-500))',
            display: 'grid', placeItems: 'center', color: 'white', marginBottom: 12,
          }}>
            <Wand2 size={18} />
          </div>
          <div style={{ fontWeight: 600 }}>Draft a contract</div>
          <div className="small muted" style={{ marginTop: 4 }}>Generate from curated clause libraries with deterministic rendering.</div>
        </div>
        <div className="card" style={{ padding: 18, cursor: 'pointer' }} onClick={() => navigate('/review')}>
          <div style={{
            width: 36, height: 36, borderRadius: 8,
            background: 'var(--warn-bg)', color: 'var(--warn-400)',
            display: 'grid', placeItems: 'center', marginBottom: 12,
          }}>
            <Shield size={18} />
          </div>
          <div style={{ fontWeight: 600 }}>Review counterparty paper</div>
          <div className="small muted" style={{ marginTop: 4 }}>Upload and get risk flags, missing clauses, and per-clause findings.</div>
        </div>
        <div className="card" style={{ padding: 18, cursor: 'pointer' }} onClick={() => navigate('/matters')}>
          <div style={{
            width: 36, height: 36, borderRadius: 8,
            background: 'var(--info-bg)', color: 'var(--brand-300)',
            display: 'grid', placeItems: 'center', marginBottom: 12,
          }}>
            <Briefcase size={18} />
          </div>
          <div style={{ fontWeight: 600 }}>Open a matter</div>
          <div className="small muted" style={{ marginTop: 4 }}>Organize documents, drafts, and analyses for a single engagement.</div>
        </div>
      </div>

      {/* Pending reviews */}
      {needsAction.length > 0 && (
        <div style={{ marginTop: 18 }}>
          <div className="card">
            <div className="card-header">
              <GitPullRequest size={14} style={{ color: 'var(--warn-400)' }} />
              <h3>Needs Your Action</h3>
              <span className="sub">{needsAction.length}</span>
            </div>
            <div style={{ padding: 4 }}>
              {needsAction.map(r => (
                <div
                  key={r.id}
                  onClick={() => navigate(`/matters/${r.matterId}`)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', cursor: 'pointer',
                    borderBottom: '1px solid var(--line-1)',
                  }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="small" style={{ fontWeight: 500 }}>{r.matterName}</div>
                    <div className="tiny muted">
                      {r.pipelineName} &middot; {r.currentStageName}
                      {r.requiredRole && <span className="chip" style={{ marginLeft: 6 }}>{r.requiredRole}</span>}
                    </div>
                  </div>
                  <div className="row" style={{ gap: 8 }}>
                    <div style={{ width: 60 }}>
                      <div className="progress-track">
                        <div className="progress-fill" style={{ width: `${r.totalStages > 0 ? Math.round((r.currentStageOrder / r.totalStages) * 100) : 0}%` }} />
                      </div>
                    </div>
                    <span className="tiny muted">{r.currentStageOrder}/{r.totalStages}</span>
                    <span className="btn primary sm">Review</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function getGreeting() {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}
