import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Plus, Trash2, GripVertical, Save, ShieldAlert, Key, ClipboardList, ArrowDown } from 'lucide-react';
import api from '../api/client';

const AVAILABLE_STEPS = [
  {
    type: 'EXTRACT_KEY_TERMS',
    label: 'Extract Key Terms',
    description: 'Extracts structured data: parties, dates, values, governing law',
    icon: Key,
    color: 'text-primary',
    bg: 'bg-primary/10',
  },
  {
    type: 'RISK_ASSESSMENT',
    label: 'Risk Assessment',
    description: 'Identifies and rates risk categories: liability, IP, termination, etc.',
    icon: ShieldAlert,
    color: 'text-warning',
    bg: 'bg-warning/10',
  },
  {
    type: 'CLAUSE_CHECKLIST',
    label: 'Clause Checklist',
    description: 'Audits standard clauses: present, weak, or missing with recommendations',
    icon: ClipboardList,
    color: 'text-success',
    bg: 'bg-success/10',
  },
];

function StepPalette({ onAdd }) {
  return (
    <div className="card h-fit">
      <h3 className="text-sm font-semibold mb-3">Step Palette</h3>
      <p className="text-xs text-text-muted mb-4">Click a step to add it to your workflow</p>
      <div className="space-y-2">
        {AVAILABLE_STEPS.map(step => {
          const Icon = step.icon;
          return (
            <button
              key={step.type}
              onClick={() => onAdd(step)}
              className="w-full text-left p-3 rounded-lg border border-border hover:border-primary/30 hover:bg-surface-el transition-colors group"
            >
              <div className="flex items-start gap-2.5">
                <div className={`w-7 h-7 rounded-md ${step.bg} flex items-center justify-center shrink-0`}>
                  <Icon className={`w-4 h-4 ${step.color}`} />
                </div>
                <div>
                  <p className="text-xs font-medium text-text-primary group-hover:text-primary transition-colors">{step.label}</p>
                  <p className="text-[10px] text-text-muted mt-0.5 leading-relaxed">{step.description}</p>
                </div>
              </div>
              <div className="flex justify-end mt-1.5">
                <Plus className="w-3.5 h-3.5 text-text-muted group-hover:text-primary" />
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function WorkflowStep({ step, index, onRemove, isLast }) {
  const Icon = AVAILABLE_STEPS.find(s => s.type === step.type)?.icon || Key;
  const info = AVAILABLE_STEPS.find(s => s.type === step.type);

  return (
    <div>
      <div className="card flex items-center gap-3 !py-3">
        <GripVertical className="w-4 h-4 text-text-muted shrink-0 cursor-grab" />
        <div className={`w-8 h-8 rounded-lg ${info?.bg || 'bg-surface-el'} flex items-center justify-center shrink-0`}>
          <Icon className={`w-4 h-4 ${info?.color || 'text-text-muted'}`} />
        </div>
        <div className="flex-1">
          <p className="text-sm font-medium text-text-primary">{step.label}</p>
          <p className="text-xs text-text-muted">{info?.description}</p>
        </div>
        <span className="text-xs text-text-muted bg-surface-el px-2 py-0.5 rounded-full shrink-0">Step {index + 1}</span>
        <button
          onClick={onRemove}
          className="text-text-muted hover:text-danger transition-colors shrink-0"
        >
          <Trash2 className="w-4 h-4" />
        </button>
      </div>
      {!isLast && (
        <div className="flex justify-center py-1">
          <ArrowDown className="w-4 h-4 text-text-muted" />
        </div>
      )}
    </div>
  );
}

export default function WorkflowBuilderPage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [steps, setSteps] = useState([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const addStep = (stepDef) => {
    setSteps(prev => [...prev, { type: stepDef.type, label: stepDef.label }]);
  };

  const removeStep = (index) => {
    setSteps(prev => prev.filter((_, i) => i !== index));
  };

  const handleSave = async () => {
    if (!name.trim()) { setError('Workflow name is required'); return; }
    if (steps.length === 0) { setError('Add at least one step'); return; }
    setSaving(true); setError('');
    try {
      await api.post('/workflows/definitions', { name, description, steps });
      navigate('/workflows');
    } catch (e) {
      setError(e.response?.data?.message || 'Failed to save workflow');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div>
      <button onClick={() => navigate('/workflows')} className="flex items-center gap-1.5 text-text-muted hover:text-text-primary text-sm mb-4">
        <ArrowLeft className="w-4 h-4" /> Back to Workflows
      </button>

      <h1 className="text-2xl font-bold mb-6">Build Custom Workflow</h1>

      <div className="grid grid-cols-3 gap-6">
        {/* Left: Step palette */}
        <StepPalette onAdd={addStep} />

        {/* Right: Builder canvas */}
        <div className="col-span-2 space-y-4">
          {/* Metadata */}
          <div className="card space-y-3">
            <div>
              <label className="text-xs text-text-muted mb-1 block">Workflow Name *</label>
              <input
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="e.g. My Custom Review"
                className="input-field w-full text-sm"
              />
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Description</label>
              <input
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="Describe what this workflow does…"
                className="input-field w-full text-sm"
              />
            </div>
          </div>

          {/* Steps canvas */}
          <div className="card min-h-[300px]">
            <h3 className="text-sm font-semibold mb-4">
              Workflow Steps
              <span className="text-text-muted font-normal ml-2 text-xs">({steps.length} added)</span>
            </h3>

            {steps.length === 0 ? (
              <div className="border-2 border-dashed border-border rounded-xl py-16 text-center">
                <Plus className="w-8 h-8 text-text-muted mx-auto mb-2" />
                <p className="text-text-muted text-sm">Add steps from the palette on the left</p>
                <p className="text-text-muted text-xs mt-1">Steps run sequentially in order</p>
              </div>
            ) : (
              <div>
                {steps.map((step, i) => (
                  <WorkflowStep
                    key={`${step.type}-${i}`}
                    step={step}
                    index={i}
                    onRemove={() => removeStep(i)}
                    isLast={i === steps.length - 1}
                  />
                ))}
              </div>
            )}
          </div>

          {error && (
            <div className="card border-l-4 border-danger bg-danger/5">
              <p className="text-danger text-sm">{error}</p>
            </div>
          )}

          {/* Actions */}
          <div className="flex justify-end gap-2">
            <button onClick={() => navigate('/workflows')} className="btn-secondary text-sm">
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={saving || !name || steps.length === 0}
              className="btn-primary flex items-center gap-2 text-sm"
            >
              <Save className="w-4 h-4" />
              {saving ? 'Saving…' : 'Save Workflow'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
