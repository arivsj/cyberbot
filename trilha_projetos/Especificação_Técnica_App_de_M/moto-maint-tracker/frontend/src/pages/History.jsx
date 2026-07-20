import React, { useEffect, useState } from 'react';
import useStore from '../store';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import { formatDate, formatKm } from '../utils/validators';

export default function History() {
  const { maintenanceLogs, loadMaintenanceLogs } = useStore();
  const [loading, setLoading] = useState(true);
  const [filters, setFilters] = useState({ tipo: '', startDate: '', endDate: '' });
  const [showFilters, setShowFilters] = useState(false);

  useEffect(() => {
    async function load() {
      await loadMaintenanceLogs();
      setLoading(false);
    }
    load();
  }, []);

  const filteredLogs = maintenanceLogs.filter(log => {
    if (filters.tipo && log.tipo !== filters.tipo) return false;
    if (filters.startDate && new Date(log.dataExecucao) < new Date(filters.startDate)) return false;
    if (filters.endDate && new Date(log.dataExecucao) > new Date(filters.endDate)) return false;
    return true;
  });

  const getTipoStyle = (tipo) => {
    const styles = {
      'Rotina': 'bg-blue-50 text-blue-700',
      'Revisão': 'bg-purple-50 text-purple-700',
      'Corretiva': 'bg-warning-50 text-warning-700',
    };
    return styles[tipo] || 'bg-gray-50 text-gray-700';
  };

  const handleExportPDF = async () => {
    try {
      const { api } = await import('../services/api');
      const blob = await api.reports.pdf(filters);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `relatorio_manutencao_${Date.now()}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
      useStore.getState().showToast('Relatório PDF gerado com sucesso!', 'success');
    } catch (error) {
      useStore.getState().showToast('Erro ao gerar PDF.', 'error');
    }
  };

  if (loading) return <LoadingSpinner size="lg" message="Carregando histórico..." />;

  return (
    <div className="pb-24">
      <div className="px-4 py-4">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-gray-900">Histórico de Manutenção</h2>
          <div className="flex gap-2">
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`p-2 rounded-lg transition-colors ${showFilters ? 'bg-primary-100 text-primary-700' : 'text-gray-400 hover:bg-gray-100'}`}
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z" />
              </svg>
            </button>
            <button
              onClick={handleExportPDF}
              className="p-2 rounded-lg text-gray-400 hover:bg-gray-100 transition-colors"
              title="Exportar PDF"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </button>
          </div>
        </div>

        {showFilters && (
          <div className="card mb-4 animate-fade-in">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div>
                <label className="label">Tipo</label>
                <select value={filters.tipo} onChange={(e) => setFilters({ ...filters, tipo: e.target.value })} className="input-field text-sm">
                  <option value="">Todos</option>
                  <option value="Rotina">Rotina</option>
                  <option value="Revisão">Revisão</option>
                  <option value="Corretiva">Corretiva</option>
                </select>
              </div>
              <div>
                <label className="label">Data Início</label>
                <input type="date" value={filters.startDate} onChange={(e) => setFilters({ ...filters, startDate: e.target.value })} className="input-field text-sm" />
              </div>
              <div>
                <label className="label">Data Fim</label>
                <input type="date" value={filters.endDate} onChange={(e) => setFilters({ ...filters, endDate: e.target.value })} className="input-field text-sm" />
              </div>
              <div className="flex items-end">
                <button onClick={() => setFilters({ tipo: '', startDate: '', endDate: '' })} className="btn-secondary text-sm w-full">Limpar</button>
              </div>
            </div>
          </div>
        )}

        {filteredLogs.length === 0 ? (
          <EmptyState
            icon="📜"
            title="Nenhum registro encontrado"
            message="Registre sua primeira manutenção para ver o histórico aqui."
          />
        ) : (
          <div className="space-y-3">
            {filteredLogs.map((log) => (
              <div key={log.id || log.logID} className="card hover:shadow-md transition-shadow">
                <div className="flex items-start justify-between mb-2">
                  <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${getTipoStyle(log.tipo)}`}>
                    {log.tipo}
                  </span>
                  <span className="text-xs text-gray-400">{formatDate(log.dataExecucao)}</span>
                </div>
                <p className="text-sm text-gray-600">{log.descricao || 'Sem descrição'}</p>
                <div className="flex items-center gap-3 mt-2 text-xs text-gray-400">
                  <span>📍 {formatKm(log.kmRegistro)}</span>
                  {log.kmProximaEstimativa && (
                    <span>📅 Próxima: {formatKm(log.kmProximaEstimativa)}</span>
                  )}
                </div>
                {log.parts && log.parts.length > 0 && (
                  <div className="mt-2 pt-2 border-t border-gray-100">
                    <p className="text-xs font-medium text-gray-500 mb-1">Peças trocadas:</p>
                    {log.parts.map((part) => (
                      <div key={part.id || part.partID} className="flex items-center justify-between text-xs text-gray-500 py-0.5">
                        <span>{part.nomePeca}</span>
                        <span>Vida útil: {part.vidaUtilEstimadaKm?.toLocaleString('pt-BR')} km</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
