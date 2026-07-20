import React from 'react';

export default function RoutineTasksCard({ tasks, onComplete }) {
  const pendingTasks = tasks?.filter(t => t.necessitaAviso) || [];
  const completedTasks = tasks?.filter(t => !t.necessitaAviso) || [];

  return (
    <div className="card">
      <h3 className="font-semibold text-gray-900 mb-3">
        Tarefas de Rotina
        {pendingTasks.length > 0 && (
          <span className="ml-2 px-1.5 py-0.5 bg-danger-100 text-danger-700 text-xs rounded-full font-medium">
            {pendingTasks.length} pendente(s)
          </span>
        )}
      </h3>

      {(!tasks || tasks.length === 0) ? (
        <p className="text-sm text-gray-400 text-center py-4">Nenhuma tarefa de rotina cadastrada.</p>
      ) : (
        <div className="space-y-2">
          {pendingTasks.map((task) => (
            <div key={task.taskID || task.id} className="flex items-center justify-between p-3 rounded-lg border border-danger-200 bg-danger-50/50 pulse-alert">
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-danger-800">{task.nomeTarefa}</p>
                <p className="text-xs text-danger-600">
                  A cada {task.intervaloKm?.toLocaleString('pt-BR')} km · Última: {task.ultimaExecucaoKm?.toLocaleString('pt-BR')} km
                </p>
              </div>
              <button
                onClick={() => onComplete?.(task)}
                className="ml-2 px-3 py-1.5 bg-danger-500 text-white text-xs font-medium rounded-lg hover:bg-danger-600 active:bg-danger-700 transition-colors whitespace-nowrap"
              >
                Concluir
              </button>
            </div>
          ))}

          {completedTasks.length > 0 && (
            <>
              <details className="mt-2">
                <summary className="text-xs text-gray-400 cursor-pointer hover:text-gray-600">
                  {completedTasks.length} tarefa(s) em dia
                </summary>
                <div className="mt-2 space-y-1">
                  {completedTasks.map((task) => (
                    <div key={task.taskID || task.id} className="flex items-center justify-between p-2 rounded-lg border border-success-100 bg-success-50/30">
                      <p className="text-sm text-success-700">{task.nomeTarefa}</p>
                      <span className="text-xs text-success-500">OK</span>
                    </div>
                  ))}
                </div>
              </details>
            </>
          )}
        </div>
      )}
    </div>
  );
}
