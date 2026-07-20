import { create } from 'zustand';
import { api, setAuthToken } from '../services/api';
import { offlineStorage } from '../services/offlineService';
import { syncService } from '../services/syncService';

const useStore = create((set, get) => ({
  user: null,
  bike: null,
  alerts: null,
  maintenanceLogs: [],
  routineTasks: [],
  parts: [],
  kmHistory: [],
  loading: false,
  syncing: false,
  syncMessage: '',
  error: null,
  toast: null,

  setUser: (user) => set({ user }),

  setAuthToken: (token) => {
    setAuthToken(token);
  },

  loadBike: async () => {
    set({ loading: true, error: null });
    try {
      const bike = await api.bike.get();
      await offlineStorage.saveBike(bike);
      set({ bike, loading: false });
      return bike;
    } catch (error) {
      const offlineBike = await offlineStorage.getBike();
      if (offlineBike) {
        set({ bike: offlineBike, loading: false });
        return offlineBike;
      }
      set({ loading: false, error: error.message });
      throw error;
    }
  },

  createBike: async (bikeData) => {
    set({ loading: true, error: null });
    try {
      const bike = await api.bike.create(bikeData);
      await offlineStorage.saveBike(bike);
      set({ bike, loading: false });
      return bike;
    } catch (error) {
      set({ loading: false, error: error.message });
      throw error;
    }
  },

  updateKm: async (kmAtual, force = false) => {
    set({ loading: true, error: null });
    try {
      const result = await api.bike.updateKm(kmAtual, force);
      const updatedBike = { ...get().bike, kmAtual, ultimaAtualizacaoKm: new Date() };
      await offlineStorage.saveBike(updatedBike);
      set({ bike: updatedBike, loading: false });
      get().showToast('Quilometragem atualizada com sucesso!', 'success');
      return result;
    } catch (error) {
      await offlineStorage.addPendingSync({ action: 'updateKm', endpoint: '/bike/km', data: { kmAtual, force } });
      const updatedBike = { ...get().bike, kmAtual };
      await offlineStorage.saveBike(updatedBike);
      set({ bike: updatedBike, loading: false });
      get().showToast('Quilometragem salva offline. Sincronização pendente.', 'info');
    }
  },

  loadDashboard: async () => {
    try {
      const data = await api.alerts.dashboard();
      set({ alerts: data });
      return data;
    } catch {
      const bike = await offlineStorage.getBike();
      const tasks = await offlineStorage.getRoutineTasks();
      set({ alerts: { kmAtual: bike?.kmAtual, alerts: [], parts: [], routines: tasks } });
    }
  },

  loadMaintenanceLogs: async () => {
    try {
      const logs = await api.maintenance.list();
      await offlineStorage.saveLogs(logs);
      set({ maintenanceLogs: logs });
      return logs;
    } catch {
      const logs = await offlineStorage.getLogs();
      set({ maintenanceLogs: logs });
    }
  },

  loadRoutineTasks: async () => {
    try {
      const tasks = await api.routine.list();
      await offlineStorage.saveRoutineTasks(tasks);
      set({ routineTasks: tasks });
      return tasks;
    } catch {
      const tasks = await offlineStorage.getRoutineTasks();
      set({ routineTasks: tasks });
    }
  },

  createMaintenance: async (data, images = []) => {
    try {
      const result = await api.maintenance.create(data, images);
      await get().loadMaintenanceLogs();
      get().showToast('Manutenção registrada com sucesso!', 'success');
      return result;
    } catch (error) {
      await offlineStorage.addPendingSync({ action: 'createMaintenance', data });
      await get().loadMaintenanceLogs();
      get().showToast('Manutenção salva offline.', 'info');
    }
  },

  createCorretiva: async (data, images = []) => {
    try {
      const result = await api.maintenance.corretiva(data, images);
      await get().loadMaintenanceLogs();
      get().showToast(`Manutenção corretiva registrada! Próxima troca prevista em ${result.part?.kmProximaEstimativa} km`, 'success');
      return result;
    } catch (error) {
      await offlineStorage.addPendingSync({ action: 'createPart', data });
      get().showToast('Manutenção corretiva salva offline.', 'info');
    }
  },

  createRevisao: async (data) => {
    try {
      const result = await api.maintenance.revisao(data);
      await get().loadMaintenanceLogs();
      get().showToast('Revisão registrada com sucesso!', 'success');
      return result;
    } catch (error) {
      await offlineStorage.addPendingSync({ action: 'createMaintenance', data });
      get().showToast('Revisão salva offline.', 'info');
    }
  },

  completeRoutineTask: async (taskId, kmAtual) => {
    try {
      const result = await api.routine.complete(taskId, kmAtual);
      await get().loadRoutineTasks();
      get().showToast('Tarefa concluída!', 'success');
      return result;
    } catch {
      await offlineStorage.addPendingSync({ action: 'completeRoutine', data: { taskId, kmAtual } });
      await get().loadRoutineTasks();
      get().showToast('Tarefa concluída offline.', 'info');
    }
  },

  createRoutineTask: async (data) => {
    try {
      const result = await api.routine.create(data);
      await get().loadRoutineTasks();
      get().showToast('Tarefa de rotina criada!', 'success');
      return result;
    } catch (error) {
      get().showToast('Erro ao criar tarefa.', 'error');
    }
  },

  deleteRoutineTask: async (taskId) => {
    try {
      await api.routine.delete(taskId);
      await get().loadRoutineTasks();
    } catch {
      get().showToast('Erro ao remover tarefa.', 'error');
    }
  },

  showToast: (message, type = 'info') => {
    set({ toast: { message, type, id: Date.now() } });
    setTimeout(() => {
      if (get().toast?.id === Date.now()) {
        set({ toast: null });
      }
    }, 3000);
  },

  clearToast: () => set({ toast: null }),

  setSyncing: (syncing, message = '') => set({ syncing, syncMessage: message }),

  resetStore: () => set({
    user: null, bike: null, alerts: null, maintenanceLogs: [],
    routineTasks: [], parts: [], kmHistory: [], error: null,
  }),
}));

export default useStore;
