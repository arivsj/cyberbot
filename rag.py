import os
import re
import math
import subprocess
import httpx
import db

OLLAMA_URL = "http://localhost:11434"
EMBED_MODEL = "nomic-embed-text"
CHUNK_SIZE = 500
CHUNK_OVERLAP = 50

try:
    import fitz
    HAS_PDF = True
except ImportError:
    HAS_PDF = False
    print("[rag] PyMuPDF não encontrado. PDF suportado apenas via pdftotext (poppler-utils).")

def extract_text(filepath):
    ext = os.path.splitext(filepath)[1].lower()
    if ext == ".txt":
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    elif ext == ".pdf":
        if HAS_PDF:
            try:
                doc = fitz.open(filepath)
                text = "".join(page.get_text() for page in doc)
                doc.close()
                if text.strip():
                    return text
                print(f"[rag] PDF vazio com fitz, tentando pdftotext: {filepath}")
            except Exception as e:
                print(f"[rag] Erro fitz {filepath}: {e}")
        try:
            r = subprocess.run(["pdftotext", filepath, "-"], capture_output=True, text=True, timeout=30)
            text = r.stdout.strip()
            if text:
                return text
            if r.stderr:
                print(f"[rag] pdftotext: {r.stderr[:200]}")
        except FileNotFoundError:
            print("[rag] pdftotext não encontrado. Instale poppler-utils: sudo apt install poppler-utils")
        except Exception as e:
            print(f"[rag] Erro pdftotext {filepath}: {e}")
        return None
    else:
        return None

def chunk_text(text, size=CHUNK_SIZE, overlap=CHUNK_OVERLAP):
    chunks = []
    sentences = re.split(r'(?<=[.!?])\s+', text)
    current = ""
    for s in sentences:
        if len(current) + len(s) <= size:
            current += s + " "
        else:
            if current.strip():
                chunks.append(current.strip())
            current = s + " "
    if current.strip():
        chunks.append(current.strip())
    if len(chunks) <= 1 and len(text) > size:
        for i in range(0, len(text), size - overlap):
            chunk = text[i:i + size]
            if len(chunk) > size // 4:
                chunks.append(chunk)
    return chunks

def generate_embedding(text):
    try:
        r = httpx.post(
            f"{OLLAMA_URL}/api/embeddings",
            json={"model": EMBED_MODEL, "prompt": text},
            timeout=30,
        )
        if r.status_code == 200:
            emb = r.json().get("embedding", [])
            if emb:
                return emb
        print(f"[rag] Erro embedding: status {r.status_code}")
    except Exception as e:
        print(f"[rag] Erro generate_embedding: {e}")
    return []

def cosine_similarity(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0
    return dot / (na * nb)

def index_file(filepath, name):
    text = extract_text(filepath)
    if not text:
        return False
    chunks = chunk_text(text)
    doc_id = db.save_rag_document(name)
    for i, chunk in enumerate(chunks):
        emb = generate_embedding(chunk)
        if emb:
            db.save_rag_chunk(doc_id, i, chunk, emb)
    return True

def query(question, top_k=5):
    q_emb = generate_embedding(question)
    if not q_emb:
        return []
    chunks = db.get_all_rag_chunks()
    scored = []
    for c in chunks:
        stored_emb = db.load_embedding(c.get("embedding", ""))
        if stored_emb:
            score = cosine_similarity(q_emb, stored_emb)
            scored.append((score, c))
    scored.sort(key=lambda x: -x[0])
    return scored[:top_k]

def query_with_context(question, top_k=5):
    results = query(question, top_k)
    if not results:
        return "", []
    context_parts = []
    sources = set()
    for score, chunk in results:
        if score > 0.15:
            doc = db.get_rag_document(chunk["doc_id"])
            name = doc["name"] if doc else "desconhecido"
            sources.add(name)
            context_parts.append(f"[Fonte: {name} (relevância: {score:.2f})]\n{chunk['text']}")
    return "\n\n".join(context_parts), list(sources)
