import { offlineStorage } from './offlineService';
import { api, setAuthToken, getAuthToken } from './api';

class SyncService {
  constructor() {
    this.isSyncing = false;
    this.listeners = [];
  }

  onSyncStatusChange(callback) {
    this.listeners.push(callback);
    return () => {
      this.listeners = this.listeners.filter(l => l !== callback);
    };
  }

  notifyListeners(status) {
    this.listeners.forEach(l => l(status));
  }

  async syncPending() {
    if (this.isSyncing) return;
    if (!getAuthToken()) return;

    this.isSyncing = true;
    this.notifyListeners({ syncing: true, message: 'Sincronizando dados...' });

    try {
      const pending = await offlineStorage.getPendingSyncs();
      for (const item of pending) {
        try {
          await this.executeSync(item);
          await offlineStorage.clearPendingSync(item.id);
        } catch (error) {
          console.error('Erro ao sincronizar item:', item, error);
        }
      }

      const bike = await offlineStorage.getBike();
      if (bike) {
        try {
          await api.bike.update(bike);
        } catch {
          // silently fail
        }
      }

      this.notifyListeners({ syncing: false, message: 'Dados sincronizados com sucesso!' });
    } catch (error) {
      this.notifyListeners({ syncing: false, message: 'Erro ao sincronizar dados.' });
    } finally {
      this.isSyncing = false;
    }
  }

  async executeSync(item) {
    const { action, endpoint, data } = item;
    switch (action) {
      case 'updateKm':
        return api.bike.updateKm(data.kmAtual, data.force);
      case 'createMaintenance':
        return api.maintenance.create(data);
      case 'createPart':
        return api.maintenance.addPart(data.logId, data.part);
      case 'completeRoutine':
        return api.routine.complete(data.taskId, data.kmAtual);
      default:
        throw new Error(`Ação desconhecida: ${action}`);
    }
  }

  async syncAll() {
    this.notifyListeners({ syncing: true, message: 'Carregando dados do servidor...' });
    try {
      const bike = await api.bike.get();
      const logs = await api.maintenance.list();
      const parts = await Promise.all(logs.map(l => api.maintenance.list({ logId: l.id })));
      const tasks = await api.routine.list();
      const alerts = await api.alerts.dashboard();

      await offlineStorage.saveBike(bike);
      await offlineStorage.saveLogs(logs);
      // await offlineStorage.saveParts(allParts);
      await offlineStorage.saveRoutineTasks(tasks);

      return { bike, logs, tasks, alerts };
    } catch (error) {
      throw error;
    } finally {
      this.notifyListeners({ syncing: false });
    }
  }
}

export const syncService = new SyncService();
