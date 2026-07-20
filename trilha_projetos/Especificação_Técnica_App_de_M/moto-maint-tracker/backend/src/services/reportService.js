const PDFDocument = require('pdfkit');
const { getFirestore } = require('../config/firebase');

class ReportService {
  static async generateMaintenanceReport(userId, filters = {}) {
    const db = getFirestore();

    const bikeDoc = await db.collection('bikes').doc(userId).get();
    if (!bikeDoc.exists) throw new Error('Moto não encontrada.');
    const bike = bikeDoc.data();

    let query = db.collection('maintenanceLogs')
      .where('userId', '==', userId)
      .orderBy('dataExecucao', 'desc');

    if (filters.startDate) {
      query = query.where('dataExecucao', '>=', new Date(filters.startDate));
    }
    if (filters.endDate) {
      query = query.where('dataExecucao', '<=', new Date(filters.endDate));
    }

    const logsSnapshot = await query.get();
    const logs = logsSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

    let filteredLogs = logs;
    if (filters.tipo) {
      filteredLogs = filteredLogs.filter(log => log.tipo === filters.tipo);
    }
    if (filters.minKm) {
      filteredLogs = filteredLogs.filter(log => log.kmRegistro >= filters.minKm);
    }
    if (filters.maxKm) {
      filteredLogs = filteredLogs.filter(log => log.kmRegistro <= filters.maxKm);
    }

    const partsPromises = filteredLogs.map(log =>
      db.collection('replacementParts')
        .where('logID_FK', '==', log.id || log.logID)
        .get()
        .then(snapshot => ({
          logId: log.id || log.logID,
          parts: snapshot.docs.map(d => ({ id: d.id, ...d.data() })),
        }))
    );
    const partsResults = await Promise.all(partsPromises);
    const partsByLog = Object.fromEntries(partsResults.map(r => [r.logId, r.parts]));

    return this.buildPDF(bike, filteredLogs, partsByLog);
  }

  static buildPDF(bike, logs, partsByLog) {
    return new Promise((resolve, reject) => {
      const doc = new PDFDocument({ margin: 50 });
      const chunks = [];
      doc.on('data', chunk => chunks.push(chunk));
      doc.on('end', () => resolve(Buffer.concat(chunks)));
      doc.on('error', reject);

      doc.fontSize(20).text('Relatório de Manutenção', { align: 'center' });
      doc.moveDown();
      doc.fontSize(12).text(`Moto: ${bike.apelidoMoto} - ${bike.marcaModelo}`);
      doc.text(`Ano: ${bike.anoFabricacao} | Km Atual: ${bike.kmAtual} km`);
      doc.text(`Gerado em: ${new Date().toLocaleDateString('pt-BR')}`);
      doc.moveDown(2);

      if (logs.length === 0) {
        doc.fontSize(14).text('Nenhum registro de manutenção encontrado.', { align: 'center' });
        doc.end();
        return;
      }

      for (const log of logs) {
        doc.fontSize(14).fillColor('#2563eb').text(`Manutenção - ${log.dataExecucao?.toDate?.()?.toLocaleDateString('pt-BR') || new Date(log.dataExecucao).toLocaleDateString('pt-BR')}`);
        doc.fillColor('#000').fontSize(11);
        doc.text(`Tipo: ${log.tipo}`);
        doc.text(`Km: ${log.kmRegistro} km`);
        doc.text(`Descrição: ${log.descricao || 'N/A'}`);
        doc.moveDown(0.5);

        const logParts = partsByLog[log.id || log.logID] || [];
        if (logParts.length > 0) {
          doc.fontSize(12).fillColor('#374151').text('Peças trocadas:');
          doc.fillColor('#000').fontSize(10);
          for (const part of logParts) {
            doc.text(`  • ${part.nomePeca} (Troca: ${part.kmTroca} km, Vida útil: ${part.vidaUtilEstimadaKm} km, Próxima: ${part.kmProximaEstimativa} km)`);
          }
        }
        doc.moveDown(1.5);
      }

      doc.end();
    });
  }

  static async getFilteredHistory(userId, filters = {}) {
    const db = getFirestore();

    let query = db.collection('maintenanceLogs')
      .where('userId', '==', userId)
      .orderBy('dataExecucao', 'desc');

    const logsSnapshot = await query.get();
    let logs = logsSnapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

    if (filters.tipo) {
      logs = logs.filter(log => log.tipo === filters.tipo);
    }
    if (filters.startDate) {
      logs = logs.filter(log => new Date(log.dataExecucao) >= new Date(filters.startDate));
    }
    if (filters.endDate) {
      logs = logs.filter(log => new Date(log.dataExecucao) <= new Date(filters.endDate));
    }
    if (filters.minKm) {
      logs = logs.filter(log => log.kmRegistro >= filters.minKm);
    }
    if (filters.maxKm) {
      logs = logs.filter(log => log.kmRegistro <= filters.maxKm);
    }

    const partsPromises = logs.map(log =>
      db.collection('replacementParts')
        .where('logID_FK', '==', log.id || log.logID)
        .get()
        .then(snapshot => ({
          logId: log.id || log.logID,
          parts: snapshot.docs.map(d => ({ id: d.id, ...d.data() })),
        }))
    );
    const partsResults = await Promise.all(partsPromises);
    const partsByLog = Object.fromEntries(partsResults.map(r => [r.logId, r.parts]));

    return logs.map(log => ({
      ...log,
      parts: partsByLog[log.id || log.logID] || [],
    }));
  }
}

module.exports = ReportService;
