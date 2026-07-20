const { getFirestore } = require('../config/firebase');
const CalculationService = require('./calculationService');

class AlertService {
  static async getDashboardAlerts(userId) {
    const db = getFirestore();

    const bikeDoc = await db.collection('bikes').doc(userId).get();
    if (!bikeDoc.exists) return { error: 'Moto não encontrada.', alerts: [] };
    const bike = { id: bikeDoc.id, ...bikeDoc.data() };

    const partsSnapshot = await db.collection('replacementParts')
      .where('userId', '==', userId)
      .get();
    const parts = partsSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

    const tasksSnapshot = await db.collection('routineTasks')
      .where('userId', '==', userId)
      .get();
    const routineTasks = tasksSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

    const logsSnapshot = await db.collection('maintenanceLogs')
      .where('userId', '==', userId)
      .orderBy('dataExecucao', 'desc')
      .limit(50)
      .get();
    const maintenanceLogs = logsSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

    const alerts = CalculationService.getDashboardAlerts(bike, parts, routineTasks, maintenanceLogs);

    return {
      kmAtual: bike.kmAtual,
      apelidoMoto: bike.apelidoMoto,
      marcaModelo: bike.marcaModelo,
      alerts,
      parts: parts.map(p => ({
        ...p,
        kmRestante: CalculationService.kmRemainingForPart(p.kmProximaEstimativa, bike.kmAtual),
        status: CalculationService.getKmStatus(p.kmProximaEstimativa, bike.kmAtual).status,
      })).sort((a, b) => a.kmRestante - b.kmRestante).slice(0, 5),
      routines: routineTasks.map(t => ({
        ...t,
        necessitaAviso: CalculationService.needsRoutineAlert(bike.kmAtual, t.ultimaExecucaoKm, t.intervaloKm),
      })),
    };
  }

  static async getCriticalAlerts(userId) {
    const data = await this.getDashboardAlerts(userId);
    return data.alerts.filter(a => a.severity === 'critical' || a.severity === 'high');
  }
}

module.exports = AlertService;
