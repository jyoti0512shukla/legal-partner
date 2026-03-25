import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  ArrowLeft, FileText, Shield, Workflow, Users, Clock, Plus, Upload, Trash2, X,
  Loader2, CheckCircle2, XCircle, AlertTriangle, Edit2, Play
} from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import api from '../api/client';
import MatterFindingsPanel from '../components/MatterFindingsPanel';

const DEAL_TYPES = ['SAAS_ACQUISITION', 'M_AND_A', 'NDA', 'COMMERCIAL_LEASE', 'FINANCING', 'IP_LICENSE', 'EMPLOYMENT', 'GENERAL'];
const PRACTICE_AREAS = ['CORPORATE', 'LITIGATION', 'IP', 'TAX', 'REAL_ESTATE', 'LABOR', 'BANKING', 'REGULATORY', 'OTHER'];
const STATUS_COLORS = { ACTIVE: 'bg-success/10 text-success', CLOSED: 'bg-surface-el text-text-muted', ARCHIVED: 'bg-surface-el text-text-muted' };
const DOC_STATUS_COLORS = { INDEXED: 'text-success', PENDING: 'text-warning', PROCESSING: 'text-warning', FAILED: 'text-danger' };
const MEMBER_ROLES = ['LEAD_PARTNER', 'PARTNER', 'ASSOCIATE', 'PARALEGAL', 'CLIENT_CONTACT', 'EXTERNAL'];
const ROLE_COLORS = {
  LEAD_PARTNER: 'bg-primary/10 text-primary', PARTNER: 'bg-primary/10 text-primary',
  ASSOCIATE: 'bg-warning/10 text-warning', PARALEGAL: 'bg-surface-el text-text-muted',
  CLIENT_CONTACT: 'bg-success/10 text-success', EXTERNAL: 'bg-surface-el text-text-muted',
};

const TABS = [
  { id: 'documents', label: 'Documents', icon: FileText },
  { id: 'findings', label: 'Findings', icon: Shield },
  { id: 'workflows', label: 'Workflows', icon: Workflow },
  { id: 'team', label: 'Team', icon: Users },
  { id: 'activity', label: 'Activity', icon: Clock },
];

export default function MatterDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const [matter, setMatter] = useState(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('documents');
  const [team, setTeam] = useState([]);
  const [myRole, setMyRole] = useState(null);

  const isAdmin = user?.role === 'ROLE_ADMIN';
  const isPartner = user?.role === 'ROLE_PARTNER';
  const canManage = isAdmin || myRole === 'LEAD_PARTNER';
  const canEdit = isAdmin || isPartner || myRole === 'LEAD_PARTNER';
  const canReviewFindings = isAdmin || isPartner || myRole === 'LEAD_PARTNER' || myRole === 'PARTNER';

  const fetchMatter = useCallback(() => {
    api.get(`/matters/${id}`).then(r => setMatter(r.data)).catch(() => navigate('/matters'));
  }, [id, navigate]);

  const fetchTeam = useCallback(() => {
    api.get(`/matters/${id}/team`).then(r => {
      setTeam(r.data || []);
      const me = (r.data || []).find(m => m.email === user?.email || m.userId === user?.id);
      setMyRole(me ? me.matterRole : null);
    }).catch(() => {});
  }, [id, user]);

  useEffect(() => {
    api.get(`/matters/${id}`)
      .then(r => {
        setMatter(r.data);
        // Team fetch is separate — don't fail the whole page if team endpoint errors
        api.get(`/matters/${id}/team`)
          .then(teamRes => {
            setTeam(teamRes.data || []);
            const me = (teamRes.data || []).find(m => m.email === user?.email || m.userId === user?.id);
            setMyRole(me ? me.matterRole : null);
          })
          .catch(() => setTeam([]));
      })
      .catch(() => navigate('/matters'))
      .finally(() => setLoading(false));
  }, [id, user, navigate]);

  const handleStatusChange = async (status) => {
    await api.patch(`/matters/${id}/status?status=${status}`);
    fetchMatter();
  };

  const findingSummary = matter ? { count: matter.findingCount || 0 } : null;

  if (loading) return <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 animate-spin text-text-muted" /></div>;
  if (!matter) return null;

  return (
    <div className="max-w-6xl mx-auto">
      {/* Header */}
      <div className="mb-6">
        <Link to="/matters" className="flex items-center gap-1 text-sm text-text-muted hover:text-text-primary mb-3">
          <ArrowLeft className="w-4 h-4" /> Back to Matters
        </Link>

        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="flex items-center gap-3 mb-1">
              <h1 className="text-2xl font-bold text-text-primary">{matter.name}</h1>
              <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${STATUS_COLORS[matter.status] || ''}`}>
                {matter.status}
              </span>
            </div>
            <div className="flex items-center gap-2 text-sm text-text-muted flex-wrap">
              <span>{matter.matterRef}</span>
              <span>·</span>
              <span>{matter.clientName}</span>
              {matter.practiceArea && <><span>·</span><span>{matter.practiceArea.replace('_', ' ')}</span></>}
              {matter.dealType && <><span>·</span><span className="text-primary">{matter.dealType.replace(/_/g, ' ')}</span></>}
            </div>
            {matter.description && <p className="text-sm text-text-secondary mt-2">{matter.description}</p>}
          </div>

          {canEdit && (
            <div className="flex items-center gap-2 shrink-0">
              <select value={matter.status} onChange={e => handleStatusChange(e.target.value)}
                className="input-field text-xs py-1">
                <option value="ACTIVE">Active</option>
                <option value="CLOSED">Closed</option>
                <option value="ARCHIVED">Archived</option>
              </select>
            </div>
          )}
        </div>

        {/* Stats bar */}
        <div className="flex items-center gap-4 mt-3 text-xs text-text-muted">
          <span className="flex items-center gap-1"><FileText className="w-3.5 h-3.5" /> {matter.documentCount} documents</span>
          {findingSummary && findingSummary.count > 0 && (
            <span className="flex items-center gap-1"><Shield className="w-3.5 h-3.5" /> {findingSummary.count} findings</span>
          )}
          <span className="flex items-center gap-1"><Users className="w-3.5 h-3.5" /> {team.length} team members</span>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-border mb-6">
        {TABS.map(t => {
          const Icon = t.icon;
          return (
            <button key={t.id} onClick={() => setTab(t.id)}
              className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                tab === t.id ? 'border-primary text-primary' : 'border-transparent text-text-muted hover:text-text-primary'
              }`}>
              <Icon className="w-4 h-4" /> {t.label}
            </button>
          );
        })}
      </div>

      {/* Tab content */}
      {tab === 'documents' && <DocumentsTab matterId={id} canUpload={true} onDocUploaded={fetchMatter} />}
      {tab === 'findings' && <MatterFindingsPanel matterId={id} readOnly={!canReviewFindings} />}
      {tab === 'workflows' && <WorkflowsTab matterId={id} matterRef={matter.matterRef} matterName={matter.name} />}
      {tab === 'team' && <TeamTab matterId={id} team={team} canManage={canManage} onTeamChanged={fetchTeam} />}
      {tab === 'activity' && <ActivityTab matterId={id} />}
    </div>
  );
}

// ── Documents Tab ──────────────────────────────────────────────────────

function DocumentsTab({ matterId, canUpload, onDocUploaded }) {
  const [docs, setDocs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const navigate = useNavigate();

  const fetchDocs = () => {
    api.get(`/documents?matterId=${matterId}&page=0&size=100`)
      .then(r => setDocs(r.data?.content || r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(fetchDocs, [matterId]);

  const handleUpload = async (files) => {
    if (!files?.length) return;
    setUploading(true);
    try {
      for (const file of files) {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('matterId', matterId);
        await api.post('/documents/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
      }
      fetchDocs();
      if (onDocUploaded) onDocUploaded();
    } catch (e) {
      alert(e.response?.data?.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    handleUpload(e.dataTransfer.files);
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-4"><Loader2 className="w-4 h-4 animate-spin" /> Loading documents...</div>;

  return (
    <div className="space-y-4">
      {/* Upload area */}
      {canUpload && (
        <div
          onDragOver={e => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          className={`border-2 border-dashed rounded-lg p-6 text-center transition-colors ${
            dragOver ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
          }`}
        >
          {uploading ? (
            <div className="flex items-center justify-center gap-2 text-text-muted">
              <Loader2 className="w-5 h-5 animate-spin" /> Uploading...
            </div>
          ) : (
            <>
              <Upload className="w-8 h-8 text-text-muted mx-auto mb-2" />
              <p className="text-sm text-text-muted mb-2">Drag & drop files here, or</p>
              <label className="btn-primary text-sm cursor-pointer inline-flex items-center gap-1.5">
                <Plus className="w-4 h-4" /> Choose Files
                <input type="file" multiple className="hidden" onChange={e => handleUpload(e.target.files)} />
              </label>
              <p className="text-[10px] text-text-muted mt-2">PDF, DOCX, HTML — auto-linked to this matter</p>
            </>
          )}
        </div>
      )}

      {/* Document list */}
      {docs.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No documents uploaded to this matter yet.</p>
      ) : (
        <div className="space-y-2">
          {docs.map(doc => (
            <div key={doc.id} onClick={() => navigate(`/intelligence?documentId=${doc.id}`)}
              className="card p-3 flex items-center justify-between cursor-pointer hover:border-primary/30 transition-colors">
              <div className="flex items-center gap-3">
                <FileText className="w-5 h-5 text-text-muted" />
                <div>
                  <div className="text-sm font-medium text-text-primary">{doc.fileName || doc.name}</div>
                  <div className="text-[10px] text-text-muted">
                    {doc.documentType && <span>{doc.documentType} · </span>}
                    {new Date(doc.uploadedAt || doc.createdAt).toLocaleDateString()}
                  </div>
                </div>
              </div>
              <span className={`text-[10px] font-medium ${DOC_STATUS_COLORS[doc.processingStatus] || 'text-text-muted'}`}>
                {doc.processingStatus || 'PENDING'}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Workflows Tab ──────────────────────────────────────────────────────

function WorkflowsTab({ matterId, matterRef, matterName }) {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get(`/workflows/runs?matter=${encodeURIComponent(matterRef)}`)
      .then(r => setRuns(r.data?.content || r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [matterRef]);

  const RUN_STATUS_ICONS = { COMPLETED: CheckCircle2, FAILED: XCircle, RUNNING: Loader2, PENDING: Clock, CANCELLED: XCircle };
  const RUN_STATUS_COLORS = { COMPLETED: 'text-success', FAILED: 'text-danger', RUNNING: 'text-warning', PENDING: 'text-text-muted', CANCELLED: 'text-text-muted' };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-4"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <button onClick={() => navigate('/workflows')} className="btn-primary text-sm flex items-center gap-1.5">
          <Play className="w-4 h-4" /> Run Workflow
        </button>
      </div>

      {runs.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No workflow runs for this matter yet.</p>
      ) : (
        <div className="space-y-2">
          {runs.map(run => {
            const Icon = RUN_STATUS_ICONS[run.status] || Clock;
            return (
              <div key={run.id} onClick={() => navigate(`/workflows/run/${run.id}`)}
                className="card p-3 flex items-center justify-between cursor-pointer hover:border-primary/30 transition-colors">
                <div className="flex items-center gap-3">
                  <Icon className={`w-5 h-5 ${RUN_STATUS_COLORS[run.status] || ''} ${run.status === 'RUNNING' ? 'animate-spin' : ''}`} />
                  <div>
                    <div className="text-sm font-medium text-text-primary">{run.workflowName}</div>
                    <div className="text-[10px] text-text-muted">
                      {new Date(run.startedAt).toLocaleString()} · {run.stepCount} steps
                    </div>
                  </div>
                </div>
                <span className={`text-xs font-medium ${RUN_STATUS_COLORS[run.status] || ''}`}>{run.status}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ── Team Tab ──────────────────────────────────────────────────────────

function TeamTab({ matterId, team, canManage, onTeamChanged }) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('ASSOCIATE');
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState(null);

  const handleAdd = async () => {
    if (!email.trim()) return;
    setAdding(true);
    try {
      await api.post(`/matters/${matterId}/team`, { email: email.trim(), matterRole: role });
      setEmail('');
      onTeamChanged();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to add member');
    } finally {
      setAdding(false);
    }
  };

  const handleRemove = async (memberId) => {
    if (!confirm('Remove this team member?')) return;
    setRemoving(memberId);
    try {
      await api.delete(`/matters/${matterId}/team/${memberId}`);
      onTeamChanged();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to remove member');
    } finally {
      setRemoving(null);
    }
  };

  return (
    <div className="space-y-4">
      {/* Add member form */}
      {canManage && (
        <div className="card p-4">
          <p className="text-xs font-medium text-text-muted mb-2">Add Team Member</p>
          <div className="flex gap-2">
            <input type="email" placeholder="email@firm.com" value={email}
              onChange={e => setEmail(e.target.value)}
              className="input-field flex-1 text-sm" />
            <select value={role} onChange={e => setRole(e.target.value)} className="input-field text-sm">
              {MEMBER_ROLES.map(r => <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>)}
            </select>
            <button onClick={handleAdd} disabled={adding || !email.trim()} className="btn-primary text-sm flex items-center gap-1.5">
              {adding ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
              Add
            </button>
          </div>
        </div>
      )}

      {/* Team list */}
      {team.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No team members added yet.</p>
      ) : (
        <div className="space-y-2">
          {team.map(member => (
            <div key={member.id} className="card p-3 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-primary text-sm font-medium">
                  {(member.displayName || member.email || '?')[0].toUpperCase()}
                </div>
                <div>
                  <div className="text-sm font-medium text-text-primary">{member.displayName || member.email}</div>
                  {member.displayName && <div className="text-[10px] text-text-muted">{member.email}</div>}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${ROLE_COLORS[member.matterRole] || ''}`}>
                  {member.matterRole.replace(/_/g, ' ')}
                </span>
                {canManage && (
                  <button onClick={() => handleRemove(member.id)} disabled={removing === member.id}
                    className="text-text-muted hover:text-danger p-1" title="Remove member">
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Activity Tab ──────────────────────────────────────────────────────

function ActivityTab({ matterId }) {
  const [activities, setActivities] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Combine findings + docs into a timeline
    Promise.all([
      api.get(`/matters/${matterId}/findings`).catch(() => ({ data: [] })),
      api.get(`/documents?matterId=${matterId}&page=0&size=100`).catch(() => ({ data: { content: [] } })),
    ]).then(([findingsRes, docsRes]) => {
      const items = [];

      // Findings
      for (const f of (findingsRes.data || [])) {
        items.push({
          time: f.createdAt,
          type: 'finding',
          title: `Finding: ${f.title}`,
          detail: `${f.severity} · ${f.findingType.replace(/_/g, ' ')}`,
          severity: f.severity,
        });
        if (f.reviewedAt) {
          items.push({
            time: f.reviewedAt,
            type: 'review',
            title: `Finding reviewed: ${f.title}`,
            detail: `Status: ${f.status}`,
          });
        }
      }

      // Documents
      for (const d of (docsRes.data?.content || docsRes.data || [])) {
        items.push({
          time: d.uploadedAt || d.createdAt,
          type: 'document',
          title: `Document uploaded: ${d.fileName || d.name}`,
          detail: d.processingStatus || 'PENDING',
        });
      }

      items.sort((a, b) => new Date(b.time) - new Date(a.time));
      setActivities(items);
    }).finally(() => setLoading(false));
  }, [matterId]);

  const TYPE_ICONS = { finding: AlertTriangle, review: CheckCircle2, document: FileText, workflow: Workflow };
  const TYPE_COLORS = { finding: 'text-warning', review: 'text-success', document: 'text-primary', workflow: 'text-text-muted' };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-4"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  if (activities.length === 0) return <p className="text-text-muted text-sm text-center py-6">No activity yet.</p>;

  return (
    <div className="space-y-1">
      {activities.map((a, i) => {
        const Icon = TYPE_ICONS[a.type] || Clock;
        return (
          <div key={i} className="flex items-start gap-3 py-2">
            <div className="mt-0.5">
              <Icon className={`w-4 h-4 ${TYPE_COLORS[a.type] || 'text-text-muted'}`} />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-sm text-text-primary">{a.title}</div>
              <div className="text-[10px] text-text-muted">{a.detail}</div>
            </div>
            <div className="text-[10px] text-text-muted shrink-0">
              {new Date(a.time).toLocaleString()}
            </div>
          </div>
        );
      })}
    </div>
  );
}
