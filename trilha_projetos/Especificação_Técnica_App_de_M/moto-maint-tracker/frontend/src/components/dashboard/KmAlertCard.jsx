import React from 'react';

export default function KmAlertCard({ kmAtual, criticalAlerts }) {
  const criticalKm = criticalAlerts?.find(a => a.type === 'critical_km');

  if (!criticalKm) {
    return (
      <div className="card border-l-4 border-l-success-400 bg-success-50/50">
        <div className="flex items-start gap-3">
          <span className="text-2xl">✅</span>
          <div>
            <h3 className="font-semibold text-gray-900">Quilometragem OK</h3>
            <p className="text-sm text-gray-600">Nenhuma revisão crítica pendente.</p>
            <p className="text-xs text-gray-400 mt-1">{Number(kmAtual).toLocaleString('pt-BR')} km atuais</p>
          </div>
        </div>
      </div>
    );
  }

  const isOverdue = criticalKm.remaining <= 0;

  return (
    <div className={`card border-l-4 ${isOverdue ? 'border-l-danger-500 bg-danger-50/50 pulse-alert' : 'border-l-warning-500 bg-warning-50/50'}`}>
      <div className="flex items-start gap-3">
        <span className="text-2xl">{isOverdue ? '🚨' : '⚠️'}</span>
        <div>
          <h3 className="font-semibold text-gray-900">Alerta Crítico de Km</h3>
          <p className="text-sm text-gray-700 mt-1">{criticalKm.message}</p>
          <div className="mt-2 flex items-center gap-2">
            <div className={`text-xs font-medium px-2 py-0.5 rounded-full ${isOverdue ? 'bg-danger-100 text-danger-700' : 'bg-warning-100 text-warning-700'}`}>
              {isOverdue ? `${Math.abs(criticalKm.remaining).toLocaleString('pt-BR')} km acima do limite` : `${criticalKm.remaining.toLocaleString('pt-BR')} km restantes`}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
