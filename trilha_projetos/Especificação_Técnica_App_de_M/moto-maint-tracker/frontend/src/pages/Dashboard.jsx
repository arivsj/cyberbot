import React, { useEffect, useState } from 'react';
import useStore from '../store';
import KmAlertCard from '../components/dashboard/KmAlertCard';
import UpcomingPartsCard from '../components/dashboard/UpcomingPartsCard';
import RoutineTasksCard from '../components/dashboard/RoutineTasksCard';
import FAB from '../components/ui/FAB';
import KmUpdateModal from '../components/ui/KmUpdateModal';
import Modal from '../components/ui/Modal';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import { useKmUpdate } from '../hooks/useKmUpdate';

export default function Dashboard() {
  const { alerts, loadDashboard, routineTasks, loadRoutineTasks, completeRoutineTask } = useStore();
  const { showKmModal, setShowKmModal, newKm, setNewKm, error: kmError, loading: kmLoading, handleUpdateKm } = useKmUpdate();
  const [showFabMenu, setShowFabMenu] = useState(false);
  const [showKmFabModal, setShowKmFabModal] = useState(false);
  const [fabKmValue, setFabKmValue] = useState('');
  const [fabKmError, setFabKmError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      await Promise.all([loadDashboard(), loadRoutineTasks()]);
      setLoading(false);
    }
    load();
  }, []);

  const handleFabKmUpdate = async () => {
    const km = parseInt(fabKmValue, 10);
    if (isNaN(km) || km < 0) {
      setFabKmError('Informe um valor válido.');
      return;
    }
    setFabKmError('');
    await handleUpdateKm(km);
    setShowKmFabModal(false);
    setFabKmValue('');
    setShowFabMenu(false);
    loadDashboard();
  };

  const [taskToComplete, setTaskToComplete] = useState(null);

  const handleCompleteTask = async (task) => {
    setTaskToComplete(task);
    setShowKmFabModal(true);
    setFabKmValue('');
    setFabKmError('');
  };

  const confirmCompleteTask = async () => {
    const km = parseInt(fabKmValue, 10);
    if (isNaN(km) || km < 0) {
      setFabKmError('Informe um valor de km válido.');
      return;
    }
    setFabKmError('');
    await completeRoutineTask(taskToComplete?.taskID || taskToComplete?.id, km);
    setShowKmFabModal(false);
    setFabKmValue('');
    setTaskToComplete(null);
    setShowFabMenu(false);
    loadDashboard();
  };

  if (loading) return <LoadingSpinner size="lg" message="Carregando painel..." />;

  return (
    <div className="pb-24">
      <div className="px-4 py-4 space-y-4">
        <KmAlertCard kmAtual={alerts?.kmAtual} criticalAlerts={alerts?.alerts} />

        <UpcomingPartsCard parts={alerts?.parts} />

        <RoutineTasksCard
          tasks={alerts?.routines}
          onComplete={handleCompleteTask}
        />
      </div>

      <FAB onClick={() => setShowFabMenu(!showFabMenu)} />

      {showFabMenu && (
        <div className="fixed inset-0 z-40" onClick={() => setShowFabMenu(false)}>
          <div className="absolute bottom-24 right-6 flex flex-col gap-2" onClick={(e) => e.stopPropagation()}>
            <button
              onClick={() => { setShowKmFabModal(true); setFabKmValue(''); setFabKmError(''); setShowFabMenu(false); }}
              className="px-4 py-2 bg-white rounded-lg shadow-lg text-sm font-medium text-gray-700 hover:bg-gray-50 flex items-center gap-2"
            >
              <span className="text-lg">📏</span> Atualizar Km
            </button>
          </div>
        </div>
      )}

      <KmUpdateModal
        open={showKmModal}
        onClose={() => setShowKmModal(false)}
        kmValue={newKm}
        onKmChange={setNewKm}
        onSubmit={handleUpdateKm}
        error={kmError}
        loading={kmLoading}
      />

      <Modal open={showKmFabModal} onClose={() => { setShowKmFabModal(false); setTaskToComplete(null); }} title={taskToComplete ? 'Concluir Tarefa' : 'Atualizar Quilometragem'}>
        {taskToComplete && (
          <p className="text-sm text-gray-600 mb-3">
            Informe a quilometragem atual para registrar a conclusão de <strong>{taskToComplete.nomeTarefa}</strong>.
          </p>
        )}
        <div className="space-y-4">
          <div>
            <label className="label">Quilometragem Atual (km)</label>
            <input
              type="number"
              value={fabKmValue}
              onChange={(e) => { setFabKmValue(e.target.value); setFabKmError(''); }}
              placeholder="Ex: 15000"
              className={`input-field ${fabKmError ? 'input-error' : ''}`}
              autoFocus
              min="0"
              step="1"
            />
            {fabKmError && <p className="error-text">{fabKmError}</p>}
          </div>
          <div className="flex gap-3">
            <button onClick={() => { setShowKmFabModal(false); setTaskToComplete(null); }} className="btn-secondary flex-1">
              Cancelar
            </button>
            <button
              onClick={taskToComplete ? confirmCompleteTask : handleFabKmUpdate}
              disabled={!fabKmValue}
              className="btn-primary flex-1"
            >
              {taskToComplete ? 'Concluir' : 'Atualizar'}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
