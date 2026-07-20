const admin = require('firebase-admin');

let firebaseApp = null;

function initializeFirebase() {
  if (firebaseApp) return firebaseApp;

  const serviceAccount = {
    projectId: process.env.FIREBASE_PROJECT_ID,
    privateKey: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
    clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
  };

  firebaseApp = admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
  });

  return firebaseApp;
}

function getFirestore() {
  if (!firebaseApp) initializeFirebase();
  return admin.firestore();
}

function getAuth() {
  if (!firebaseApp) initializeFirebase();
  return admin.auth();
}

module.exports = { initializeFirebase, getFirestore, getAuth };
