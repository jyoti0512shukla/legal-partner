import { useState, useEffect } from 'react';
import { ClipboardList, CheckCircle2, XCircle, AlertCircle, ChevronDown, ChevronUp } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

const CHECKLIST = [
  {
    category: 'Parties & Execution',
    items: [
      'Full legal names of all parties are stated',
      'Registered office addresses are included',
      'Authorized signatories are identified',
      'Execution date / effective date is defined',
    ],
  },
  {
    category: 'Scope & Obligations',
    items: [
      'Scope of work / services is clearly defined',
      'Deliverables and acceptance criteria are specified',
      'Timelines and milestones are set out',
      'Change order / variation procedure exists',
    ],
  },
  {
    category: 'Payment Terms',
    items: [
      'Payment amounts and schedule are defined',
      'Invoice and payment timeline is stated',
      'Late payment interest is addressed',
      'Currency and tax treatment (GST) is specified',
    ],
  },
  {
    category: 'Liability & Indemnity',
    items: [
      'Liability is capped (recommend mutual cap)',
      'Consequential loss exclusion is present',
      'Indemnity obligations are mutual / balanced',
      'Insurance requirements are specified',
    ],
  },
  {
    category: 'IP & Confidentiality',
    items: [
      'Ownership of work product / IP is defined',
      'License grant (if any) is clearly stated',
      'Confidentiality obligations are present',
      'Post-termination confidentiality survival period stated',
    ],
  },
  {
    category: 'Term & Termination',
    items: [
      'Contract duration is specified',
      'Renewal / auto-renewal mechanism addressed',
      'Termination for cause provisions exist',
      'Termination for convenience provisions exist',
      'Consequences of termination (handover, payment) addressed',
    ],
  },
  {
    category: 'Dispute Resolution',
    items: [
      'Governing law is specified (Indian law?)',
      'Jurisdiction / courts are identified',
      'Arbitration clause present (if applicable)',
      'Escalation / notice-before-dispute procedure exists',
    ],
  },
  {
    category: 'Compliance & Regulatory',
    items: [
      'Data protection obligations addressed (IT Act / DPDP)',
      'Anti-bribery / anti-corruption clause present',
      'Force majeure clause present',
      'Applicable Indian regulations identified',
    ],
  },
];

function CategorySection({ category, items, checks, onToggle }) {
  const [open, setOpen] = useState(true);
  const done = items.filter((_, i) => checks[`${category}:${i}`] === 'yes').length;
  const issues = items.filter((_, i) => checks[`${category}:${i}`] === 'no').length;

  return (
    <div className="card mb-3">
      <button
        onClick={() => setOpen(o => !o)}
        className="flex items-center justify-between w-full"
      >
        <div className="flex items-center gap-3">
          <h3 className="font-semibold text-text-primary">{category}</h3>
          <span className="text-xs text-success">{done}/{items.length} done</span>
          {issues > 0 && <span className="text-xs text-danger">{issues} issue{issues > 1 ? 's' : ''}</span>}
        </div>
        {open ? <ChevronUp className="w-4 h-4 text-text-muted" /> : <ChevronDown className="w-4 h-4 text-text-muted" />}
      </button>

      {open && (
        <div className="mt-4 space-y-3">
          {items.map((item, i) => {
            const key = `${category}:${i}`;
            const val = checks[key];
            return (
              <div key={i} className="flex items-start gap-3">
                <div className="flex gap-1.5 mt-0.5 shrink-0">
                  <button
                    onClick={() => onToggle(key, 'yes')}
                    className={`p-1 rounded transition-colors ${val === 'yes' ? 'text-success' : 'text-text-muted hover:text-success'}`}
                    title="Yes / Present"
                  >
                    <CheckCircle2 className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => onToggle(key, 'issue')}
                    className={`p-1 rounded transition-colors ${val === 'issue' ? 'text-warning' : 'text-text-muted hover:text-warning'}`}
                    title="Present but needs attention"
                  >
                    <AlertCircle className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => onToggle(key, 'no')}
                    className={`p-1 rounded transition-colors ${val === 'no' ? 'text-danger' : 'text-text-muted hover:text-danger'}`}
                    title="Missing / Issue"
                  >
                    <XCircle className="w-4 h-4" />
                  </button>
                </div>
                <span className={`text-sm leading-relaxed ${
                  val === 'yes' ? 'text-text-secondary line-through' :
                  val === 'no' ? 'text-danger' :
                  val === 'issue' ? 'text-warning' : 'text-text-primary'
                }`}>{item}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function ContractReviewPage() {
  const [checks, setChecks] = useState({});
  const [docName, setDocName] = useState('');
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState('');

  useEffect(() => {
    api.get('/documents?size=100').then(r => setDocs(r.data.content || [])).catch(() => {});
  }, []);

  const handleDocSelect = (id) => {
    setDocId(id);
    const doc = docs.find(d => d.id === id);
    setDocName(doc?.fileName || '');
    setChecks({});
  };

  const onToggle = (key, val) => {
    setChecks(prev => {
      if (prev[key] === val) {
        const next = { ...prev };
        delete next[key];
        return next;
      }
      return { ...prev, [key]: val };
    });
  };

  const totalItems = CHECKLIST.reduce((sum, c) => sum + c.items.length, 0);
  const doneCount = Object.values(checks).filter(v => v === 'yes').length;
  const issueCount = Object.values(checks).filter(v => v === 'no').length;
  const attentionCount = Object.values(checks).filter(v => v === 'issue').length;
  const progress = Math.round((doneCount / totalItems) * 100);

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Contract Review Checklist</h1>

      <div className="card mb-6">
        <div className="flex items-end gap-4">
          <div className="flex-1">
            <label className="text-xs text-text-muted mb-1 block">Select Document (optional — for reference)</label>
            <select value={docId} onChange={e => handleDocSelect(e.target.value)} className="input-field w-full text-sm">
              <option value="">General review (no document)</option>
              {docs.map(d => <option key={d.id} value={d.id}>{d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}</option>)}
            </select>
          </div>
          <button onClick={() => setChecks({})} className="btn-secondary text-sm">Reset</button>
        </div>
        {docName && <p className="text-xs text-text-muted mt-2">Reviewing: <span className="text-primary">{docName}</span></p>}
      </div>

      {/* Progress summary */}
      <div className="card mb-6">
        <div className="flex items-center justify-between mb-3">
          <span className="text-sm font-medium">Review Progress</span>
          <span className="text-sm text-text-muted">{doneCount} / {totalItems} items</span>
        </div>
        <div className="h-2 bg-surface-el rounded-full overflow-hidden mb-3">
          <div
            className="h-full bg-primary rounded-full transition-all duration-500"
            style={{ width: `${progress}%` }}
          />
        </div>
        <div className="flex gap-4 text-xs">
          <span className="text-success">{doneCount} Present</span>
          <span className="text-warning">{attentionCount} Needs attention</span>
          <span className="text-danger">{issueCount} Missing / Issue</span>
        </div>
      </div>

      {CHECKLIST.map(section => (
        <CategorySection
          key={section.category}
          category={section.category}
          items={section.items}
          checks={checks}
          onToggle={onToggle}
        />
      ))}

      <div className="card mt-4 text-center py-6">
        <ClipboardList className="w-8 h-8 text-text-muted mx-auto mb-2" />
        <p className="text-sm text-text-muted">
          Use <CheckCircle2 className="inline w-3 h-3 text-success" /> Present,{' '}
          <AlertCircle className="inline w-3 h-3 text-warning" /> Needs attention,{' '}
          <XCircle className="inline w-3 h-3 text-danger" /> Missing for each clause
        </p>
      </div>
    </div>
  );
}
