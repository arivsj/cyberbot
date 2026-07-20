import React, { useState } from 'react';
import useStore from '../store';
import Modal from '../components/ui/Modal';
import { validators } from '../utils/validators';

export default function Settings() {
  const { bike, updateKm, routineTasks, deleteRoutineTask, showToast } = useStore();
  const [editBike, setEditBike] = useState(false);
  const [showForceKm, setShowForceKm] = useState(false);
  const [forceKm, setForceKm] = useState('');
  const [forceKmError, setForceKmError] = useState('');

  const [bikeForm, setBikeForm] = useState({
    apelidoMoto: bike?.apelidoMoto || '',
    marcaModelo: bike?.marcaModelo || '',
    anoFabricacao: bike?.anoFabricacao || '',
  });

  const handleForceKm = async () => {
    const km = parseInt(forceKm, 10);
    if (isNaN(km) || km < 0) {
      setForceKmError('Valor inválido.');
      return;
    }
    try {
      await updateKm(km, true);
      setShowForceKm(false);
      setForceKm('');
      setForceKmError('');
      showToast('Quilometragem ajustada manualmente!', 'success');
    } catch (err) {
      setForceKmError(err.message);
    }
  };

  const handleDeleteTask = async (taskId, taskName) => {
    if (window.confirm(`Remover tarefa "${taskName}"?`)) {
      await deleteRoutineTask(taskId);
    }
  };

  return (
    <div className="pb-24">
      <div className="px-4 py-4 space-y-6">
        <section>
          <h2 className="text-lg font-semibold text-gray-900 mb-3">Minha Moto</h2>
          <div className="card space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <p className="font-medium">{bike?.apelidoMoto || 'Sem apelido'}</p>
                <p className="text-sm text-gray-500">{bike?.marcaModelo || 'Sem modelo'}</p>
              </div>
              <span className="text-3xl">🏍️</span>
            </div>
            <div className="flex items-center justify-between py-2 border-t border-gray-100">
              <span className="text-sm text-gray-600">Km Atual</span>
              <span className="font-semibold">{bike?.kmAtual?.toLocaleString('pt-BR')} km</span>
            </div>
            <div className="flex items-center justify-between py-2 border-t border-gray-100">
              <span className="text-sm text-gray-600">Ano de Fabricação</span>
              <span className="font-medium">{bike?.anoFabricacao || '-'}</span>
            </div>
            <div className="flex gap-2 pt-2">
              <button onClick={() => setEditBike(true)} className="btn-secondary flex-1 text-sm">Editar</button>
              <button onClick={() => setShowForceKm(true)} className="btn-secondary flex-1 text-sm">Ajustar Km</button>
            </div>
          </div>
        </section>

        <section>
          <h2 className="text-lg font-semibold text-gray-900 mb-3">Tarefas de Rotina</h2>
          {routineTasks.length === 0 ? (
            <p className="text-sm text-gray-400 text-center py-4">Nenhuma tarefa cadastrada.</p>
          ) : (
            <div className="space-y-2">
              {routineTasks.map((task) => (
                <div key={task.taskID || task.id} className="card flex items-center justify-between">
                  <div>
                    <p className="font-medium text-sm">{task.nomeTarefa}</p>
                    <p className="text-xs text-gray-400">A cada {task.intervaloKm?.toLocaleString('pt-BR')} km</p>
                  </div>
                  <button
                    onClick={() => handleDeleteTask(task.taskID || task.id, task.nomeTarefa)}
                    className="text-danger-500 text-sm font-medium hover:text-danger-600"
                  >
                    Remover
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>

        <section>
          <h2 className="text-lg font-semibold text-gray-900 mb-3">Sobre</h2>
          <div className="card space-y-2 text-sm text-gray-600">
            <p>Moto-Maint Tracker v1.0.0</p>
            <p>Sistema inteligente de gestão de manutenção veicular.</p>
          </div>
        </section>
      </div>

      <Modal open={editBike} onClose={() => setEditBike(false)} title="Editar Moto">
        <form onSubmit={async (e) => {
          e.preventDefault();
          try {
            const { api } = await import('../services/api');
            await api.bike.update({
              ...bikeForm,
              anoFabricacao: bikeForm.anoFabricacao ? Number(bikeForm.anoFabricacao) : null,
            });
            useStore.getState().loadBike();
            setEditBike(false);
            showToast('Dados atualizados!', 'success');
          } catch (err) {
            showToast(err.message, 'error');
          }
        }} className="space-y-4">
          <div>
            <label className="label">Apelido</label>
            <input type="text" value={bikeForm.apelidoMoto} onChange={(e) => setBikeForm({ ...bikeForm, apelidoMoto: e.target.value })} className="input-field" />
          </div>
          <div>
            <label className="label">Marca / Modelo</label>
            <input type="text" value={bikeForm.marcaModelo} onChange={(e) => setBikeForm({ ...bikeForm, marcaModelo: e.target.value })} className="input-field" />
          </div>
          <div>
            <label className="label">Ano de Fabricação</label>
            <input type="number" value={bikeForm.anoFabricacao} onChange={(e) => setBikeForm({ ...bikeForm, anoFabricacao: e.target.value })} className="input-field" min="1900" max="2030" />
          </div>
          <button type="submit" className="btn-primary w-full">Salvar</button>
        </form>
      </Modal>

      <Modal open={showForceKm} onClose={() => setShowForceKm(false)} title="Ajuste Manual de Km">
        <p className="text-sm text-gray-600 mb-4">Use para corrigir a quilometragem se necessário (pode retroceder).</p>
        <div className="space-y-4">
          <input type="number" value={forceKm} onChange={(e) => { setForceKm(e.target.value); setForceKmError(''); }}
            placeholder="Nova quilometragem" className={`input-field ${forceKmError ? 'input-error' : ''}`} min="0" step="1" />
          {forceKmError && <p className="error-text">{forceKmError}</p>}
          <button onClick={handleForceKm} disabled={!forceKm} className="btn-primary w-full">Aplicar Ajuste</button>
        </div>
      </Modal>
    </div>
  );
}
