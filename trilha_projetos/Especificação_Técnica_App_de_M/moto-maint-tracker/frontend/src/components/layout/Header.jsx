import React from 'react';
import useStore from '../../store';
import { useOffline } from '../../hooks/useKmUpdate';

export default function Header({ title, onMenuClick }) {
  const { bike } = useStore();
  const { isOnline, syncing } = useOffline();

  return (
    <header className="sticky top-0 z-30 bg-white border-b border-gray-200">
      <div className="flex items-center justify-between px-4 py-3">
        <div className="flex items-center gap-3">
          <button onClick={onMenuClick} className="w-9 h-9 flex items-center justify-center rounded-lg hover:bg-gray-100">
            <svg className="w-5 h-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <div>
            <h1 className="text-lg font-bold text-gray-900 leading-tight">{title || 'Moto-Maint'}</h1>
            {bike && <p className="text-xs text-gray-500">{bike.apelidoMoto} · {formatKm(bike.kmAtual)}</p>}
          </div>
        </div>
        <div className="flex items-center gap-2">
          {!isOnline && (
            <span className="flex items-center gap-1 px-2 py-1 bg-warning-50 text-warning-600 text-xs rounded-full font-medium">
              <span className="w-1.5 h-1.5 bg-warning-500 rounded-full pulse-alert" />
              Offline
            </span>
          )}
          {syncing && (
            <span className="flex items-center gap-1 px-2 py-1 bg-primary-50 text-primary-600 text-xs rounded-full font-medium">
              <div className="w-3 h-3 border-2 border-primary-200 border-t-primary-600 rounded-full animate-spin" />
              Sinc.
            </span>
          )}
        </div>
      </div>
    </header>
  );
}

function formatKm(km) {
  if (km === null || km === undefined) return '-';
  return Number(km).toLocaleString('pt-BR') + ' km';
}
