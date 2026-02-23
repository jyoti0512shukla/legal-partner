import Sidebar from './Sidebar';
import { useAuth } from '../../hooks/useAuth';

export default function AppLayout({ children }) {
  const { user } = useAuth();

  return (
    <div className="flex min-h-screen bg-bg">
      <Sidebar />
      <main className="flex-1 p-8 overflow-auto">
        <div className="max-w-7xl mx-auto">
          <div className="mb-6 text-sm text-text-muted">
            Good {getGreeting()}, <span className="text-text-primary font-medium capitalize">{user?.username}</span>
          </div>
          {children}
        </div>
      </main>
    </div>
  );
}

function getGreeting() {
  const hour = new Date().getHours();
  if (hour < 12) return 'morning';
  if (hour < 17) return 'afternoon';
  return 'evening';
}
