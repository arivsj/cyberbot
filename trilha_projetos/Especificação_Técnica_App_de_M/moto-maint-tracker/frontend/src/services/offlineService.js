import { openDB } from 'idb';

const DB_NAME = 'moto-maint-tracker';
const DB_VERSION = 1;

let dbPromise = null;

function getDb() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('bike')) {
          db.createObjectStore('bike', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('maintenanceLogs')) {
          const logStore = db.createObjectStore('maintenanceLogs', { keyPath: 'id' });
          logStore.createIndex('dataExecucao', 'dataExecucao');
        }
        if (!db.objectStoreNames.contains('replacementParts')) {
          db.createObjectStore('replacementParts', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('routineTasks')) {
          db.createObjectStore('routineTasks', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('kmHistory')) {
          db.createObjectStore('kmHistory', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('pendingSync')) {
          db.createObjectStore('pendingSync', { keyPath: 'id', autoIncrement: true });
        }
      },
    });
  }
  return dbPromise;
}

export const offlineStorage = {
  async saveBike(bike) {
    const db = await getDb();
    await db.put('bike', bike);
  },

  async getBike() {
    const db = await getDb();
    return db.get('bike', 'current');
  },

  async saveLogs(logs) {
    const db = await getDb();
    const tx = db.transaction('maintenanceLogs', 'readwrite');
    for (const log of logs) {
      await tx.store.put(log);
    }
    await tx.done;
  },

  async getLogs(limit = 50) {
    const db = await getDb();
    const logs = await db.getAll('maintenanceLogs');
    return logs
      .sort((a, b) => new Date(b.dataExecucao) - new Date(a.dataExecucao))
      .slice(0, limit);
  },

  async saveParts(parts) {
    const db = await getDb();
    const tx = db.transaction('replacementParts', 'readwrite');
    for (const part of parts) {
      await tx.store.put(part);
    }
    await tx.done;
  },

  async getParts() {
    const db = await getDb();
    return db.getAll('replacementParts');
  },

  async saveRoutineTasks(tasks) {
    const db = await getDb();
    const tx = db.transaction('routineTasks', 'readwrite');
    for (const task of tasks) {
      await tx.store.put(task);
    }
    await tx.done;
  },

  async getRoutineTasks() {
    const db = await getDb();
    return db.getAll('routineTasks');
  },

  async addPendingSync(action) {
    const db = await getDb();
    return db.add('pendingSync', { ...action, timestamp: new Date().toISOString() });
  },

  async getPendingSyncs() {
    const db = await getDb();
    return db.getAll('pendingSync');
  },

  async clearPendingSync(id) {
    const db = await getDb();
    await db.delete('pendingSync', id);
  },

  async clearAll() {
    const db = await getDb();
    const tx = db.transaction(['bike', 'maintenanceLogs', 'replacementParts', 'routineTasks', 'pendingSync'], 'readwrite');
    await Promise.all([
      tx.objectStore('bike').clear(),
      tx.objectStore('maintenanceLogs').clear(),
      tx.objectStore('replacementParts').clear(),
      tx.objectStore('routineTasks').clear(),
      tx.objectStore('pendingSync').clear(),
    ]);
    await tx.done;
  },
};
