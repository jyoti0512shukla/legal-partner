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
  const [counts, setCounts] = useState({ documents: 0, matters: 0, drafts: 0 });
  const [recentDrafts, setRecentDrafts] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/review-pipelines/dashboard').catch(() => ({ data: { needsAction: [], teamActivity: [], recentlyCompleted: [] } })),
      api.get('/findings/dashboard').catch(() => ({ data: { highCount: 0, mediumCount: 0, lowCount: 0, unreviewedCount: 0, totalCount: 0, recentFindings: [], matterRiskSummary: [] } })),
      api.get('/documents?size=1').catch(() => ({ data: { totalElements: 0 } })),
      api.get('/matters').catch(() => ({ data: [] })),
      api.get('/ai/drafts').catch(() => ({ data: [] })),
    ]).then(([reviewRes, aiRes, docsRes, mattersRes, draftsRes]) => {
      setReviewData(reviewRes.data);
      setAiData(aiRes.data);
      setCounts({
        documents: docsRes.data?.totalElements || 0,
        matters: Array.isArray(mattersRes.data) ? mattersRes.data.length : 0,
        drafts: Array.isArray(draftsRes.data) ? draftsRes.data.length : 0,
      });
      setRecentDrafts(Array.isArray(draftsRes.data) ? draftsRes.data.slice(0, 5) : []);
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
        <div className="stat" onClick={() => navigate('/documents')} style={{ cursor: 'pointer' }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">Documents</span>
            <FileText size={14} style={{ color: 'var(--brand-400)' }} />
          </div>
          <div className="value">{counts.documents}</div>
          <div className="small muted">Uploaded contracts</div>
        </div>
        <div className="stat" onClick={() => navigate('/matters')} style={{ cursor: 'pointer' }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">Active Matters</span>
            <Briefcase size={14} style={{ color: 'var(--teal-400)' }} />
          </div>
          <div className="value">{counts.matters}</div>
          <div className="small muted">Cases & projects</div>
        </div>
        <div className="stat" onClick={() => navigate('/draft')} style={{ cursor: 'pointer' }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">Drafts Generated</span>
            <Wand2 size={14} style={{ color: 'var(--brand-400)' }} />
          </div>
          <div className="value">{counts.drafts}</div>
          <div className="small muted">AI-generated contracts</div>
        </div>
        <div className="stat">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <span className="label">Risk Findings</span>
            <AlertTriangle size={14} style={{ color: highCount > 0 ? 'var(--danger-400)' : 'var(--text-4)' }} />
          </div>
          <div className="value" style={{ color: highCount > 0 ? 'var(--danger-400)' : undefined }}>
            {highCount + mediumCount + lowCount || 0}
          </div>
          {highCount > 0 ? (
            <div className="small" style={{ color: 'var(--danger-400)' }}>{highCount} high risk</div>
          ) : (
            <div className="small muted">No risk analysis yet</div>
          )}
        </div>
      </div>

      {/* Main content: recent drafts + getting started */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: 18 }}>
        {/* Recent drafts */}
        <div className="card">
          <div className="card-header">
            <Wand2 size={14} style={{ color: 'var(--brand-400)' }} />
            <h3>Recent Drafts</h3>
            <span className="sub">{recentDrafts.length}</span>
            <button className="btn ghost sm" style={{ marginLeft: 'auto' }} onClick={() => navigate('/draft')}>
              View all <ArrowRight size={12} />
            </button>
          </div>
          {recentDrafts.length > 0 ? (
            <div style={{ padding: 4 }}>
              {recentDrafts.map((d, i) => (
                <div
                  key={d.id || i}
                  onClick={() => navigate('/draft')}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 12, padding: '10px 12px', cursor: 'pointer',
                    borderBottom: i < recentDrafts.length - 1 ? '1px solid var(--line-1)' : 'none',
                  }}
                >
                  <div style={{
                    width: 32, height: 32, borderRadius: 6,
                    background: d.status === 'INDEXED' ? 'var(--success-bg)' : d.status === 'FAILED' ? 'var(--danger-bg)' : 'var(--info-bg)',
                    color: d.status === 'INDEXED' ? 'var(--success-400)' : d.status === 'FAILED' ? 'var(--danger-400)' : 'var(--info-500)',
                    display: 'grid', placeItems: 'center',
                  }}>
                    {d.status === 'INDEXED' ? <CheckCircle2 size={14} /> : d.status === 'FAILED' ? <AlertTriangle size={14} /> : <Clock size={14} />}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="small" style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {d.fileName || 'Untitled Draft'}
                    </div>
                    <div className="tiny muted" style={{ marginTop: 2 }}>
                      {d.status === 'INDEXED' ? 'Completed' : d.status === 'FAILED' ? 'Failed' : 'Generating...'}
                      {d.completedClauses > 0 && ` · ${d.completedClauses} clauses`}
                      {d.durationSeconds && ` · ${Math.floor(d.durationSeconds / 60)}m${d.durationSeconds % 60}s`}
                    </div>
                  </div>
                  <div className="tiny muted">
                    {d.createdAt && new Date(d.createdAt).toLocaleDateString()}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-3)', fontSize: 13 }}>
              No drafts yet. Click "New draft" to generate your first contract.
            </div>
          )}
        </div>

        {/* Getting started / activity */}
        <div className="card">
          <div className="card-header">
            <Brain size={14} style={{ color: 'var(--brand-400)' }} />
            <h3>Getting Started</h3>
          </div>
          <div style={{ padding: 16 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                <div style={{ width: 24, height: 24, borderRadius: '50%', background: counts.documents > 0 ? 'var(--success-bg)' : 'var(--bg-3)', color: counts.documents > 0 ? 'var(--success-400)' : 'var(--text-4)', display: 'grid', placeItems: 'center', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>
                  {counts.documents > 0 ? '✓' : '1'}
                </div>
                <div>
                  <div className="small" style={{ fontWeight: 500, color: counts.documents > 0 ? 'var(--text-2)' : 'var(--text-1)' }}>Upload a contract</div>
                  <div className="tiny muted">Upload PDF, DOCX, or HTML contracts for AI analysis</div>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                <div style={{ width: 24, height: 24, borderRadius: '50%', background: counts.drafts > 0 ? 'var(--success-bg)' : 'var(--bg-3)', color: counts.drafts > 0 ? 'var(--success-400)' : 'var(--text-4)', display: 'grid', placeItems: 'center', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>
                  {counts.drafts > 0 ? '✓' : '2'}
                </div>
                <div>
                  <div className="small" style={{ fontWeight: 500, color: counts.drafts > 0 ? 'var(--text-2)' : 'var(--text-1)' }}>Generate your first draft</div>
                  <div className="tiny muted">Describe a deal and let AI draft a complete contract</div>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                <div style={{ width: 24, height: 24, borderRadius: '50%', background: 'var(--bg-3)', color: 'var(--text-4)', display: 'grid', placeItems: 'center', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>3</div>
                <div>
                  <div className="small" style={{ fontWeight: 500 }}>Run risk assessment</div>
                  <div className="tiny muted">104 structured questions identify gaps and risks</div>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                <div style={{ width: 24, height: 24, borderRadius: '50%', background: counts.matters > 0 ? 'var(--success-bg)' : 'var(--bg-3)', color: counts.matters > 0 ? 'var(--success-400)' : 'var(--text-4)', display: 'grid', placeItems: 'center', fontSize: 11, fontWeight: 700, flexShrink: 0 }}>
                  {counts.matters > 0 ? '✓' : '4'}
                </div>
                <div>
                  <div className="small" style={{ fontWeight: 500, color: counts.matters > 0 ? 'var(--text-2)' : 'var(--text-1)' }}>Organize into matters</div>
                  <div className="tiny muted">Group documents, drafts, and analyses by case</div>
                </div>
              </div>
            </div>
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
