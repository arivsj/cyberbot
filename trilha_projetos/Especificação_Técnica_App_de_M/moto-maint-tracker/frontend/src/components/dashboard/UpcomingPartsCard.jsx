import React from 'react';
import { getSeverityColor } from '../../utils/validators';

export default function UpcomingPartsCard({ parts }) {
  if (!parts || parts.length === 0) {
    return (
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Próximas Trocas</h3>
        <p className="text-sm text-gray-400 text-center py-4">Nenhuma peça cadastrada.</p>
      </div>
    );
  }

  const topParts = parts.slice(0, 5);

  return (
    <div className="card">
      <h3 className="font-semibold text-gray-900 mb-3">Próximas Trocas Iminentes</h3>
      <div className="space-y-2">
        {topParts.map((part) => (
          <div
            key={part.partID || part.id}
            className={`flex items-center justify-between p-2.5 rounded-lg border ${getSeverityColor(part.status)}`}
          >
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">{part.nomePeca}</p>
              <p className="text-xs opacity-75">
                {part.kmRestante <= 0
                  ? `${Math.abs(part.kmRestante).toLocaleString('pt-BR')} km acima do limite`
                  : `${part.kmRestante.toLocaleString('pt-BR')} km restantes`}
              </p>
            </div>
            <span className="text-xs font-semibold ml-2">
              {part.kmProximaEstimativa?.toLocaleString('pt-BR')} km
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
