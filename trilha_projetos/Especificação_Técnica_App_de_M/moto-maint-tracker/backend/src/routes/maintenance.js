const express = require('express');
const router = express.Router();
const { getFirestore } = require('../config/firebase');
const { authenticate } = require('../middleware/auth');
const CalculationService = require('../services/calculationService');
const multer = require('multer');
const path = require('path');
const { v4: uuidv4 } = require('uuid');

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    const allowedMimes = ['image/jpeg', 'image/png'];
    if (allowedMimes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error('Formato de imagem inválido. Use JPEG ou PNG.'), false);
    }
  },
});

router.use(authenticate);

router.get('/', async (req, res) => {
  try {
    const db = getFirestore();
    const { tipo, startDate, endDate, minKm, maxKm } = req.query;

    let query = db.collection('maintenanceLogs')
      .where('userId', '==', req.user.uid)
      .orderBy('dataExecucao', 'desc');

    const snapshot = await query.get();
    let logs = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

    if (tipo) logs = logs.filter(l => l.tipo === tipo);
    if (startDate) logs = logs.filter(l => new Date(l.dataExecucao) >= new Date(startDate));
    if (endDate) logs = logs.filter(l => new Date(l.dataExecucao) <= new Date(endDate));
    if (minKm) logs = logs.filter(l => l.kmRegistro >= Number(minKm));
    if (maxKm) logs = logs.filter(l => l.kmRegistro <= Number(maxKm));

    const partsPromises = logs.map(log =>
      db.collection('replacementParts')
        .where('logID_FK', '==', log.id)
        .get()
        .then(snapshot => ({
          logId: log.id,
          parts: snapshot.docs.map(d => ({ id: d.id, ...d.data() })),
        }))
    );
    const partsResults = await Promise.all(partsPromises);
    const partsByLog = Object.fromEntries(partsResults.map(r => [r.logId, r.parts]));

    res.json(logs.map(log => ({ ...log, parts: partsByLog[log.id] || [] })));
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/', upload.array('imagens', 3), async (req, res) => {
  try {
    const db = getFirestore();
    const { kmRegistro, tipo, descricao, proximaRevisaoKm } = req.body;

    if (!kmRegistro || !tipo) {
      return res.status(400).json({ error: 'Campos obrigatórios: kmRegistro, tipo.' });
    }

    const validTypes = ['Rotina', 'Revisão', 'Corretiva'];
    if (!validTypes.includes(tipo)) {
      return res.status(400).json({ error: 'Tipo inválido. Use: Rotina, Revisão ou Corretiva.' });
    }

    if (req.files && req.files.length > 0) {
      if (req.files.some(f => !['image/jpeg', 'image/png'].includes(f.mimetype))) {
        return res.status(400).json({ error: 'Apenas imagens JPEG e PNG são permitidas.' });
      }
    }

    const logId = uuidv4();
    const maintenanceLog = {
      logID: logId,
      userId: req.user.uid,
      kmRegistro: Number(kmRegistro),
      dataExecucao: new Date(),
      tipo,
      descricao: descricao || '',
      imagens: req.files ? req.files.map(f => `data:${f.mimetype};base64,${f.buffer.toString('base64')}`) : [],
      kmProximaEstimativa: proximaRevisaoKm ? Number(proximaRevisaoKm) : null,
    };

    await db.collection('maintenanceLogs').doc(logId).set(maintenanceLog);

    if (tipo === 'Revisão' && req.body.peças) {
      const pecas = JSON.parse(req.body.peças);
      for (const peca of pecas) {
        const partId = uuidv4();
        const part = {
          partID: partId,
          logID_FK: logId,
          userId: req.user.uid,
          nomePeca: peca.nomePeca,
          kmTroca: Number(kmRegistro),
          vidaUtilEstimadaKm: Number(peca.vidaUtilEstimadaKm),
          kmProximaEstimativa: CalculationService.calculateUpcomingKm(Number(kmRegistro), Number(peca.vidaUtilEstimadaKm)),
        };
        await db.collection('replacementParts').doc(partId).set(part);
      }
    }

    res.status(201).json(maintenanceLog);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/:logId/parts', async (req, res) => {
  try {
    const db = getFirestore();
    const { logId } = req.params;
    const { nomePeca, kmTroca, vidaUtilEstimadaKm } = req.body;

    if (!nomePeca || kmTroca === undefined || vidaUtilEstimadaKm === undefined) {
      return res.status(400).json({ error: 'Campos obrigatórios: nomePeca, kmTroca, vidaUtilEstimadaKm.' });
    }

    if (vidaUtilEstimadaKm <= 0) {
      return res.status(400).json({ error: 'vidaUtilEstimadaKm deve ser maior que zero.' });
    }

    const partId = uuidv4();
    const part = {
      partID: partId,
      logID_FK: logId,
      userId: req.user.uid,
      nomePeca,
      kmTroca: Number(kmTroca),
      vidaUtilEstimadaKm: Number(vidaUtilEstimadaKm),
      kmProximaEstimativa: CalculationService.calculateUpcomingKm(Number(kmTroca), Number(vidaUtilEstimadaKm)),
    };

    await db.collection('replacementParts').doc(partId).set(part);
    res.status(201).json(part);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/corretiva', upload.array('imagens', 3), async (req, res) => {
  try {
    const db = getFirestore();
    const { kmTroca, nomePeca, vidaUtilEstimadaKm, descricao } = req.body;

    if (!nomePeca || kmTroca === undefined || vidaUtilEstimadaKm === undefined) {
      return res.status(400).json({ error: 'Campos obrigatórios: nomePeca, kmTroca, vidaUtilEstimadaKm.' });
    }

    if (Number(vidaUtilEstimadaKm) <= 0) {
      return res.status(400).json({ error: 'vidaUtilEstimadaKm deve ser maior que zero.' });
    }

    const logId = uuidv4();
    const partId = uuidv4();

    const maintenanceLog = {
      logID: logId,
      userId: req.user.uid,
      kmRegistro: Number(kmTroca),
      dataExecucao: new Date(),
      tipo: 'Corretiva',
      descricao: descricao || `Troca de ${nomePeca}`,
      imagens: req.files ? req.files.map(f => `data:${f.mimetype};base64,${f.buffer.toString('base64')}`) : [],
    };

    const part = {
      partID: partId,
      logID_FK: logId,
      userId: req.user.uid,
      nomePeca,
      kmTroca: Number(kmTroca),
      vidaUtilEstimadaKm: Number(vidaUtilEstimadaKm),
      kmProximaEstimativa: CalculationService.calculateUpcomingKm(Number(kmTroca), Number(vidaUtilEstimadaKm)),
    };

    await Promise.all([
      db.collection('maintenanceLogs').doc(logId).set(maintenanceLog),
      db.collection('replacementParts').doc(partId).set(part),
    ]);

    res.status(201).json({ log: maintenanceLog, part });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/revisao', async (req, res) => {
  try {
    const db = getFirestore();
    const { kmRegistro, descricao, proximaRevisaoKm, pecas } = req.body;

    if (!kmRegistro) {
      return res.status(400).json({ error: 'Campo obrigatório: kmRegistro.' });
    }

    const logId = uuidv4();
    const maintenanceLog = {
      logID: logId,
      userId: req.user.uid,
      kmRegistro: Number(kmRegistro),
      dataExecucao: new Date(),
      tipo: 'Revisão',
      descricao: descricao || 'Revisão preventiva',
      kmProximaEstimativa: proximaRevisaoKm ? Number(proximaRevisaoKm) : Number(kmRegistro) + 5000,
    };

    await db.collection('maintenanceLogs').doc(logId).set(maintenanceLog);

    if (pecas && Array.isArray(pecas)) {
      for (const peca of pecas) {
        const partId = uuidv4();
        const part = {
          partID: partId,
          logID_FK: logId,
          userId: req.user.uid,
          nomePeca: peca.nomePeca,
          kmTroca: Number(kmRegistro),
          vidaUtilEstimadaKm: Number(peca.vidaUtilEstimadaKm),
          kmProximaEstimativa: CalculationService.calculateUpcomingKm(Number(kmRegistro), Number(peca.vidaUtilEstimadaKm)),
        };
        await db.collection('replacementParts').doc(partId).set(part);
      }
    }

    res.status(201).json(maintenanceLog);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
