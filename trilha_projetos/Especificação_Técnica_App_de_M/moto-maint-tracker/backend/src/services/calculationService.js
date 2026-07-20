class CalculationService {
  static calculateUpcomingKm(kmTroca, vidaUtilEstimadaKm) {
    return kmTroca + vidaUtilEstimadaKm;
  }

  static needsRoutineAlert(kmAtual, ultimaExecucaoKm, intervaloKm) {
    return (kmAtual - ultimaExecucaoKm) >= intervaloKm;
  }

  static kmRemainingForPart(kmProximaEstimativa, kmAtual) {
    return Math.max(0, kmProximaEstimativa - kmAtual);
  }

  static getKmStatus(kmProximaEstimativa, kmAtual) {
    const remaining = this.kmRemainingForPart(kmProximaEstimativa, kmAtual);
    if (remaining <= 0) return { status: 'overdue', remaining, label: 'Vencido' };
    if (remaining <= 500) return { status: 'critical', remaining, label: 'Crítico' };
    if (remaining <= 1000) return { status: 'warning', remaining, label: 'Atenção' };
    return { status: 'ok', remaining, label: 'OK' };
  }

  static getDashboardAlerts(bike, parts, routineTasks, maintenanceLogs) {
    const kmAtual = bike.kmAtual;
    const alerts = [];

    for (const task of routineTasks) {
      if (this.needsRoutineAlert(kmAtual, task.ultimaExecucaoKm, task.intervaloKm)) {
        const overdue = kmAtual - (task.ultimaExecucaoKm + task.intervaloKm);
        alerts.push({
          type: 'routine',
          taskId: task.taskID,
          nome: task.nomeTarefa,
          message: `Tarefa de rotina pendente: ${task.nomeTarefa}`,
          overdueKm: overdue,
          severity: 'high',
        });
      }
    }

    for (const part of parts) {
      const remaining = this.kmRemainingForPart(part.kmProximaEstimativa, kmAtual);
      const status = this.getKmStatus(part.kmProximaEstimativa, kmAtual);
      if (status.status !== 'ok') {
        alerts.push({
          type: 'part',
          partId: part.partID,
          nome: part.nomePeca,
          kmProximaEstimativa: part.kmProximaEstimativa,
          remaining,
          status: status.status,
          message: `${part.nomePeca}: ${remaining <= 0 ? 'Vencida' : `Apenas ${remaining} km restantes`}`,
          severity: status.status === 'overdue' ? 'critical' : status.status === 'critical' ? 'high' : 'medium',
        });
      }
    }

    const criticalKmAlert = this.getCriticalKmAlert(bike, maintenanceLogs);
    if (criticalKmAlert) {
      alerts.push(criticalKmAlert);
    }

    alerts.sort((a, b) => {
      const severityOrder = { critical: 0, high: 1, medium: 2, low: 3 };
      return (severityOrder[a.severity] || 99) - (severityOrder[b.severity] || 99);
    });

    return alerts;
  }

  static getCriticalKmAlert(bike, maintenanceLogs) {
    const lastReview = maintenanceLogs
      .filter(log => log.tipo === 'Revisão')
      .sort((a, b) => b.dataExecucao - a.dataExecucao)[0];

    if (!lastReview || !lastReview.kmProximaEstimativa) return null;

    const kmAtual = bike.kmAtual;
    const remaining = this.kmRemainingForPart(lastReview.kmProximaEstimativa, kmAtual);

    if (remaining <= 1000) {
      return {
        type: 'critical_km',
        message: `Atenção! Sua moto precisa ser revisada antes de ${lastReview.kmProximaEstimativa} km`,
        kmProximaEstimativa: lastReview.kmProximaEstimativa,
        remaining,
        severity: remaining <= 0 ? 'critical' : 'high',
      };
    }
    return null;
  }
}

module.exports = CalculationService;
