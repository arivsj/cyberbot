const express = require('express');
const router = express.Router();
const { getFirestore } = require('../config/firebase');
const { authenticate } = require('../middleware/auth');
const { v4: uuidv4 } = require('uuid');

router.use(authenticate);

router.get('/', async (req, res) => {
  try {
    const db = getFirestore();
    const snapshot = await db.collection('routineTasks')
      .where('userId', '==', req.user.uid)
      .get();

    const tasks = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
    res.json(tasks);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/', async (req, res) => {
  try {
    const db = getFirestore();
    const { nomeTarefa, intervaloKm } = req.body;

    if (!nomeTarefa || !intervaloKm) {
      return res.status(400).json({ error: 'Campos obrigatórios: nomeTarefa, intervaloKm.' });
    }

    if (typeof intervaloKm !== 'number' || intervaloKm <= 0 || !Number.isInteger(intervaloKm)) {
      return res.status(400).json({ error: 'intervaloKm deve ser um número inteiro positivo.' });
    }

    const taskId = uuidv4();
    const task = {
      taskID: taskId,
      userId: req.user.uid,
      nomeTarefa,
      intervaloKm: Number(intervaloKm),
      ultimaExecucaoKm: req.body.ultimaExecucaoKm || 0,
    };

    await db.collection('routineTasks').doc(taskId).set(task);
    res.status(201).json(task);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.put('/:taskId', async (req, res) => {
  try {
    const db = getFirestore();
    const { taskId } = req.params;
    const { nomeTarefa, intervaloKm } = req.body;

    const updates = {};
    if (nomeTarefa) updates.nomeTarefa = nomeTarefa;
    if (intervaloKm) updates.intervaloKm = Number(intervaloKm);

    await db.collection('routineTasks').doc(taskId).update(updates);
    const doc = await db.collection('routineTasks').doc(taskId).get();
    res.json({ id: doc.id, ...doc.data() });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.post('/:taskId/complete', async (req, res) => {
  try {
    const db = getFirestore();
    const { taskId } = req.params;
    const { kmAtual } = req.body;

    if (kmAtual === undefined || typeof kmAtual !== 'number') {
      return res.status(400).json({ error: 'kmAtual é obrigatório para completar a tarefa.' });
    }

    const taskDoc = await db.collection('routineTasks').doc(taskId).get();
    if (!taskDoc.exists) {
      return res.status(404).json({ error: 'Tarefa não encontrada.' });
    }

    await db.collection('routineTasks').doc(taskId).update({
      ultimaExecucaoKm: Number(kmAtual),
    });

    await db.collection('maintenanceLogs').add({
      logID: uuidv4(),
      userId: req.user.uid,
      kmRegistro: Number(kmAtual),
      dataExecucao: new Date(),
      tipo: 'Rotina',
      descricao: `Rotina concluída: ${taskDoc.data().nomeTarefa}`,
    });

    const updatedDoc = await db.collection('routineTasks').doc(taskId).get();
    res.json({ id: updatedDoc.id, ...updatedDoc.data() });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.delete('/:taskId', async (req, res) => {
  try {
    const db = getFirestore();
    await db.collection('routineTasks').doc(req.params.taskId).delete();
    res.json({ message: 'Tarefa removida com sucesso.' });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
