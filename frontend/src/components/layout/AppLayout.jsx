import { useLocation } from 'react-router-dom';
import { Search, Sparkles, Bell, Home, ChevronRight } from 'lucide-react';
import Sidebar from './Sidebar';

const PAGE_TITLES = {
  '/': 'Dashboard',
  '/draft': 'Draft Contract',
  '/review': 'Contract Review',
  '/risk': 'Contract Review',
  '/matters': 'Matters',
  '/documents': 'Documents',
  '/intelligence': 'Ask AI',
  '/compare': 'Compare',
  '/extraction': 'Extraction',
  '/clause-library': 'Clause Library',
  '/workflows': 'AI Agents',
  '/settings': 'Settings',
  '/playbooks': 'Playbooks',
  '/audit': 'Audit Log',
};

export default function AppLayout({ children }) {
  const location = useLocation();
  const pageTitle = PAGE_TITLES[location.pathname] || '';

  return (
    <div className="app-shell">
      <Sidebar />
      <div className="main-panel">
        <div className="topbar">
          <div className="row small" style={{ color: 'var(--text-3)' }}>
            <Home size={14} />
            <ChevronRight size={12} />
            <span style={{ color: 'var(--text-1)', fontWeight: 500 }}>{pageTitle}</span>
          </div>
          <div className="search-bar">
            <Search size={16} className="search-icon" />
            <input placeholder="Search matters, documents, clauses, clients..." />
            <kbd>&#x2318;K</kbd>
          </div>
          <div className="topbar-right">
            <button className="icon-btn" title="What's new">
              <Sparkles size={16} />
            </button>
            <button className="icon-btn" title="Notifications">
              <Bell size={16} />
              <span className="dot"></span>
            </button>
          </div>
        </div>
        <div className="content-area">
          {children}
        </div>
      </div>
    </div>
  );
}
