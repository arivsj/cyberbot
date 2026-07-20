import React, { useEffect, useState } from 'react';
import useStore from './store';
import Toast from './components/ui/Toast';
import Header from './components/layout/Header';
import LoadingSpinner from './components/ui/LoadingSpinner';
import Onboarding from './pages/Onboarding';
import Dashboard from './pages/Dashboard';
import Maintenance from './pages/Maintenance';
import History from './pages/History';
import Settings from './pages/Settings';

const PAGES = {
  dashboard: { component: Dashboard, label: 'Painel', icon: '📊' },
  maintenance: { component: Maintenance, label: 'Manutenção', icon: '🔧' },
  history: { component: History, label: 'Histórico', icon: '📜' },
  settings: { component: Settings, label: 'Config', icon: '⚙️' },
};

export default function App() {
  const { user, bike, loading, error, setUser, loadBike, createBike, showToast } = useStore();
  const [currentPage, setCurrentPage] = useState('dashboard');
  const [appLoading, setAppLoading] = useState(true);
  const [showNav, setShowNav] = useState(false);

  useEffect(() => {
    async function initApp() {
      try {
        const savedUser = localStorage.getItem('moto_user');
        if (savedUser) {
          setUser(JSON.parse(savedUser));
          try {
            await loadBike();
          } catch {
            // User has no bike yet - onboarding
          }
        }
      } catch (err) {
        console.error('Init error:', err);
      } finally {
        setAppLoading(false);
      }
    }
    initApp();
  }, []);

  const handleLogin = async () => {
    try {
      const mockUser = {
        uid: 'mock-user-' + Date.now(),
        email: 'usuario@exemplo.com',
        displayName: 'Usuario Moto',
      };
      localStorage.setItem('moto_user', JSON.stringify(mockUser));
      setUser(mockUser);
      showToast('Login realizado com sucesso!', 'success');
    } catch (err) {
      showToast('Erro ao fazer login.', 'error');
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('moto_user');
    useStore.getState().resetStore();
    setUser(null);
  };

  if (appLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <LoadingSpinner size="lg" message="Iniciando Moto-Maint Tracker..." />
      </div>
    );
  }

  if (!user) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-primary-600 to-primary-800 flex flex-col items-center justify-center px-6">
        <div className="w-20 h-20 bg-white/20 rounded-2xl flex items-center justify-center mb-6">
          <span className="text-4xl">🏍️</span>
        </div>
        <h1 className="text-2xl font-bold text-white mb-2">Moto-Maint Tracker</h1>
        <p className="text-primary-200 text-sm mb-8 text-center">Gerencie a manutenção da sua moto<br />de forma inteligente</p>
        <button onClick={handleLogin} className="bg-white text-primary-700 px-8 py-3 rounded-xl font-semibold shadow-lg hover:bg-gray-50 active:scale-95 transition-all flex items-center gap-3">
          <svg className="w-5 h-5" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/></svg>
          Entrar com Google
        </button>
        <p className="text-primary-300 text-xs mt-4">Simulação de autenticação</p>
      </div>
    );
  }

  if (!bike) {
    return <Onboarding />;
  }

  const CurrentPageComponent = PAGES[currentPage]?.component || Dashboard;

  return (
    <div className="min-h-screen bg-gray-50 max-w-lg mx-auto relative">
      <Header
        title={PAGES[currentPage]?.label}
        onMenuClick={() => setShowNav(!showNav)}
      />

      <main className="pb-20">
        <CurrentPageComponent />
      </main>

      {/* Bottom Navigation */}
      <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 z-30 max-w-lg mx-auto">
        <div className="flex items-center justify-around h-16">
          {Object.entries(PAGES).map(([key, page]) => (
            <button
              key={key}
              onClick={() => { setCurrentPage(key); setShowNav(false); }}
              className={`flex flex-col items-center justify-center flex-1 h-full transition-colors ${
                currentPage === key ? 'text-primary-600' : 'text-gray-400 hover:text-gray-600'
              }`}
            >
              <span className="text-xl leading-none mb-0.5">{page.icon}</span>
              <span className="text-[10px] font-medium">{page.label}</span>
            </button>
          ))}
        </div>
      </nav>

      {/* Side Menu (mobile) */}
      {showNav && (
        <div className="fixed inset-0 z-40" onClick={() => setShowNav(false)}>
          <div className="absolute top-0 left-0 bottom-0 w-64 bg-white shadow-xl animate-slide-up" onClick={e => e.stopPropagation()}>
            <div className="p-4 border-b border-gray-100">
              <div className="flex items-center gap-3 mb-3">
                <span className="text-3xl">🏍️</span>
                <div>
                  <p className="font-semibold text-gray-900">{bike?.apelidoMoto}</p>
                  <p className="text-xs text-gray-500">{bike?.marcaModelo}</p>
                </div>
              </div>
              <p className="text-sm font-medium text-primary-600">{Number(bike?.kmAtual || 0).toLocaleString('pt-BR')} km</p>
            </div>
            <div className="p-2">
              {Object.entries(PAGES).map(([key, page]) => (
                <button
                  key={key}
                  onClick={() => { setCurrentPage(key); setShowNav(false); }}
                  className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors ${
                    currentPage === key ? 'bg-primary-50 text-primary-700 font-medium' : 'text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  <span className="text-lg">{page.icon}</span>
                  {page.label}
                </button>
              ))}
            </div>
            <div className="absolute bottom-0 left-0 right-0 p-4 border-t border-gray-100">
              <button onClick={handleLogout} className="flex items-center gap-2 text-sm text-gray-500 hover:text-danger-500 transition-colors">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                </svg>
                Sair
              </button>
            </div>
          </div>
        </div>
      )}

      <Toast />
    </div>
  );
}
