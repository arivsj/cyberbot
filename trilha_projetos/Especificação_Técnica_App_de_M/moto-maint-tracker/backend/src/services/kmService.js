const { getFirestore } = require('../config/firebase');

class KmService {
  static async updateKm(userId, newKm, force = false) {
    const db = getFirestore();
    const bikeRef = db.collection('bikes').doc(userId);
    const bike = await bikeRef.get();

    if (!bike.exists) {
      throw new Error('Moto não encontrada.');
    }

    const bikeData = bike.data();
    const oldKm = bikeData.kmAtual || 0;

    if (!force && newKm < oldKm) {
      throw new Error('A quilometragem não pode retroceder. Use o ajuste manual se necessário.');
    }

    await bikeRef.update({
      kmAtual: newKm,
      ultimaAtualizacaoKm: new Date(),
    });

    await db.collection('kmHistory').add({
      userId,
      oldKm,
      newKm,
      dataRegistro: new Date(),
      force,
    });

    return { oldKm, newKm };
  }

  static async getKmHistory(userId, limit = 50) {
    const db = getFirestore();
    const snapshot = await db.collection('kmHistory')
      .where('userId', '==', userId)
      .orderBy('dataRegistro', 'desc')
      .limit(limit)
      .get();

    return snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
  }
}

module.exports = KmService;
