const express = require('express');
const router = express.Router();
const { getFirestore } = require('../config/firebase');
const KmService = require('../services/kmService');
const { authenticate } = require('../middleware/auth');

router.use(authenticate);

router.get('/', async (req, res) => {
  try {
    const db = getFirestore();
    const doc = await db.collection('bikes').doc(req.user.uid).get();
    if (!doc.exists) {
      return res.status(404).json({ error: 'Moto não cadastrada.' });
    }
    res.json({ id: doc.id, ...doc.data() });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/', async (req, res) => {
  try {
    const db = getFirestore();
    const { apelidoMoto, marcaModelo, anoFabricacao, kmAtual, fotoRepresentativaURL } = req.body;

    if (!apelidoMoto || !marcaModelo || kmAtual === undefined) {
      return res.status(400).json({ error: 'Campos obrigatórios: apelidoMoto, marcaModelo, kmAtual.' });
    }

    if (typeof kmAtual !== 'number' || kmAtual < 0 || !Number.isInteger(kmAtual)) {
      return res.status(400).json({ error: 'kmAtual deve ser um número inteiro positivo.' });
    }

    const existingDoc = await db.collection('bikes').doc(req.user.uid).get();
    if (existingDoc.exists) {
      return res.status(409).json({ error: 'Moto já cadastrada.' });
    }

    const bike = {
      userID: req.user.uid,
      apelidoMoto,
      marcaModelo,
      anoFabricacao: anoFabricacao || null,
      kmAtual,
      fotoRepresentativaURL: fotoRepresentativaURL || null,
      dataCadastro: new Date(),
      ultimaAtualizacaoKm: new Date(),
    };

    await db.collection('bikes').doc(req.user.uid).set(bike);

    await db.collection('kmHistory').add({
      userId: req.user.uid,
      oldKm: 0,
      newKm: kmAtual,
      dataRegistro: new Date(),
      force: false,
    });

    res.status(201).json({ id: req.user.uid, ...bike });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.put('/', async (req, res) => {
  try {
    const db = getFirestore();
    const updates = {};
    const allowedFields = ['apelidoMoto', 'marcaModelo', 'anoFabricacao', 'fotoRepresentativaURL'];

    for (const field of allowedFields) {
      if (req.body[field] !== undefined) {
        updates[field] = req.body[field];
      }
    }

    if (Object.keys(updates).length === 0) {
      return res.status(400).json({ error: 'Nenhum campo válido para atualização.' });
    }

    await db.collection('bikes').doc(req.user.uid).update(updates);
    const doc = await db.collection('bikes').doc(req.user.uid).get();
    res.json({ id: doc.id, ...doc.data() });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.patch('/km', async (req, res) => {
  try {
    const { kmAtual, force } = req.body;
    if (kmAtual === undefined || typeof kmAtual !== 'number' || !Number.isInteger(kmAtual)) {
      return res.status(400).json({ error: 'kmAtual deve ser um número inteiro válido.' });
    }
    const result = await KmService.updateKm(req.user.uid, kmAtual, !!force);
    res.json(result);
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

router.get('/km-history', async (req, res) => {
  try {
    const history = await KmService.getKmHistory(req.user.uid);
    res.json(history);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
