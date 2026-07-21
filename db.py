import sqlite3
import os
from datetime import datetime

DB_PATH = os.path.join(os.path.dirname(__file__), "financas.db")
DRIVE_DIR = os.path.join(os.path.dirname(__file__), "drive_files")

def get_conn():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn

def init():
    conn = get_conn()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS transacoes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            data TEXT NOT NULL DEFAULT (date('now', 'localtime')),
            categoria TEXT NOT NULL,
            conta TEXT NOT NULL,
            valor REAL NOT NULL,
            descricao TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS folders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            parent_id INTEGER REFERENCES folders(id),
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS drive_files (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            folder_id INTEGER REFERENCES folders(id),
            file_path TEXT NOT NULL,
            file_size INTEGER DEFAULT 0,
            mime_type TEXT DEFAULT '',
            telegram_file_id TEXT DEFAULT '',
            caption TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS trilha_projects (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            project_path TEXT NOT NULL,
            prompt_original TEXT NOT NULL,
            prompt_melhorado TEXT NOT NULL,
            status TEXT DEFAULT 'pending',
            pid INTEGER,
            created_at TEXT DEFAULT (datetime('now', 'localtime')),
            finished_at TEXT
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS whitelist (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER UNIQUE,
            username TEXT,
            label TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            date TEXT NOT NULL,
            has_risk INTEGER DEFAULT 0,
            alert_count INTEGER DEFAULT 0,
            ia_analysis TEXT DEFAULT '',
            summary TEXT DEFAULT '',
            source TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute(
        "INSERT OR IGNORE INTO settings (key, value) VALUES ('model', 'gemma4')"
    )
    conn.execute("""
        CREATE TABLE IF NOT EXISTS sysmon_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            cpu REAL DEFAULT 0,
            cpu_temp REAL DEFAULT 0,
            ram_pct REAL DEFAULT 0,
            ram_used INTEGER DEFAULT 0,
            ram_total INTEGER DEFAULT 0,
            gpu_pct REAL DEFAULT 0,
            gpu_temp REAL DEFAULT 0,
            gpu_vram_used INTEGER DEFAULT 0,
            gpu_vram_total INTEGER DEFAULT 0,
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    try:
        conn.execute("ALTER TABLE reports ADD COLUMN source TEXT DEFAULT ''")
    except:
        pass
    conn.execute("""
        CREATE TABLE IF NOT EXISTS rag_documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS rag_chunks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            doc_id INTEGER NOT NULL REFERENCES rag_documents(id),
            chunk_index INTEGER NOT NULL,
            text TEXT NOT NULL,
            embedding BLOB,
            created_at TEXT DEFAULT (datetime('now', 'localtime'))
        )
    """)
    os.makedirs(DRIVE_DIR, exist_ok=True)
    os.makedirs(TRILHA_DIR, exist_ok=True)
    conn.commit()
    conn.close()

def inserir(categoria, conta, valor, descricao=""):
    conn = get_conn()
    conn.execute(
        "INSERT INTO transacoes (categoria, conta, valor, descricao) VALUES (?, ?, ?, ?)",
        (categoria, conta, valor, descricao),
    )
    conn.commit()
    conn.close()

def _month_where(mes):
    if mes:
        return "WHERE strftime('%Y-%m', data) = ?", [mes]
    return "", []

def listar(limite=100, mes=None):
    conn = get_conn()
    where, params = _month_where(mes)
    rows = conn.execute(
        f"SELECT * FROM transacoes {where} ORDER BY id DESC LIMIT ?",
        params + [limite],
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def resumo_mes(mes=None):
    conn = get_conn()
    where, params = _month_where(mes)
    row = conn.execute(f"""
        SELECT
            COUNT(*) as total_transacoes,
            COALESCE(SUM(valor), 0) as total_gasto,
            COALESCE(AVG(valor), 0) as media_gasto,
            COALESCE(SUM(CASE WHEN strftime('%Y-%m', data) = strftime('%Y-%m', 'now') THEN valor ELSE 0 END), 0) as gasto_mes
        FROM transacoes {where}
    """, params).fetchone()
    conn.close()
    return dict(row)

def gastos_por_categoria(mes=None):
    conn = get_conn()
    where, params = _month_where(mes)
    rows = conn.execute(f"""
        SELECT categoria, COUNT(*) as qtde, SUM(valor) as total
        FROM transacoes {where}
        GROUP BY categoria
        ORDER BY total DESC
    """, params).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def gastos_por_conta(mes=None):
    conn = get_conn()
    where, params = _month_where(mes)
    rows = conn.execute(f"""
        SELECT conta, COUNT(*) as qtde, SUM(valor) as total
        FROM transacoes {where}
        GROUP BY conta
        ORDER BY total DESC
    """, params).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def gastos_por_dia(limite=30, mes=None):
    conn = get_conn()
    where, params = _month_where(mes)
    rows = conn.execute(f"""
        SELECT data, SUM(valor) as total, COUNT(*) as qtde
        FROM transacoes {where}
        GROUP BY data
        ORDER BY data DESC
        LIMIT ?
    """, params + [limite]).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def meses_disponiveis():
    conn = get_conn()
    rows = conn.execute("""
        SELECT DISTINCT strftime('%Y-%m', data) as mes
        FROM transacoes
        ORDER BY mes DESC
    """).fetchall()
    conn.close()
    return [r["mes"] for r in rows]

# ─── Drive ────────────────────────────────────────────────

def criar_pasta(nome, parent_id=None):
    conn = get_conn()
    cur = conn.execute(
        "INSERT INTO folders (name, parent_id) VALUES (?, ?)",
        (nome, parent_id),
    )
    conn.commit()
    fid = cur.lastrowid
    conn.close()
    return fid

def listar_pastas(parent_id=None):
    conn = get_conn()
    if parent_id is None:
        rows = conn.execute(
            "SELECT * FROM folders WHERE parent_id IS NULL ORDER BY name"
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT * FROM folders WHERE parent_id = ? ORDER BY name",
            (parent_id,),
        ).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def get_pasta(pasta_id):
    conn = get_conn()
    row = conn.execute("SELECT * FROM folders WHERE id = ?", (pasta_id,)).fetchone()
    conn.close()
    return dict(row) if row else None

def inserir_arquivo(name, folder_id, file_path, file_size=0, mime_type="", telegram_file_id="", caption=""):
    conn = get_conn()
    cur = conn.execute(
        "INSERT INTO drive_files (name, folder_id, file_path, file_size, mime_type, telegram_file_id, caption) VALUES (?, ?, ?, ?, ?, ?, ?)",
        (name, folder_id, file_path, file_size, mime_type, telegram_file_id, caption),
    )
    conn.commit()
    fid = cur.lastrowid
    conn.close()
    return fid

def listar_arquivos(folder_id=None):
    conn = get_conn()
    if folder_id is None:
        rows = conn.execute(
            "SELECT * FROM drive_files WHERE folder_id IS NULL ORDER BY created_at DESC"
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT * FROM drive_files WHERE folder_id = ? ORDER BY created_at DESC",
            (folder_id,),
        ).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def get_arquivo(file_id):
    conn = get_conn()
    row = conn.execute("SELECT * FROM drive_files WHERE id = ?", (file_id,)).fetchone()
    conn.close()
    return dict(row) if row else None

def deletar_arquivo(file_id):
    conn = get_conn()
    row = conn.execute("SELECT file_path FROM drive_files WHERE id = ?", (file_id,)).fetchone()
    if row:
        fp = row["file_path"]
        if fp and os.path.exists(fp):
            os.remove(fp)
        conn.execute("DELETE FROM drive_files WHERE id = ?", (file_id,))
        conn.commit()
    conn.close()

# ─── Trilha Rede ──────────────────────────────────────────

TRILHA_DIR = os.path.join(os.path.dirname(__file__), "trilha_projetos")

def criar_projeto(nome, path, original, melhorado):
    conn = get_conn()
    cur = conn.execute(
        "INSERT INTO trilha_projects (name, project_path, prompt_original, prompt_melhorado, status) VALUES (?, ?, ?, ?, 'pending')",
        (nome, path, original, melhorado),
    )
    conn.commit()
    fid = cur.lastrowid
    conn.close()
    return fid

def listar_projetos():
    conn = get_conn()
    rows = conn.execute("SELECT * FROM trilha_projects ORDER BY created_at DESC").fetchall()
    conn.close()
    return [dict(r) for r in rows]

def get_projeto(proj_id):
    conn = get_conn()
    row = conn.execute("SELECT * FROM trilha_projects WHERE id = ?", (proj_id,)).fetchone()
    conn.close()
    return dict(row) if row else None

def update_projeto_status(proj_id, status, pid=None):
    conn = get_conn()
    if pid is not None:
        conn.execute("UPDATE trilha_projects SET status = ?, pid = ? WHERE id = ?", (status, pid, proj_id))
    else:
        conn.execute("UPDATE trilha_projects SET status = ? WHERE id = ?", (status, proj_id))
    if status in ("done", "error"):
        conn.execute("UPDATE trilha_projects SET finished_at = datetime('now', 'localtime') WHERE id = ?", (proj_id,))
    conn.commit()
    conn.close()

def update_projeto_pid(proj_id, pid):
    conn = get_conn()
    conn.execute("UPDATE trilha_projects SET pid = ? WHERE id = ?", (pid, proj_id))
    conn.commit()
    conn.close()

def update_arquivo_path(file_id, file_path):
    conn = get_conn()
    conn.execute("UPDATE drive_files SET file_path = ? WHERE id = ?", (file_path, file_id))
    conn.commit()
    conn.close()

# ─── Whitelist ────────────────────────────────────────────

def whitelist_add(user_id, username="", label=""):
    conn = get_conn()
    try:
        cur = conn.execute(
            "INSERT INTO whitelist (user_id, username, label) VALUES (?, ?, ?)",
            (user_id, username or None, label),
        )
        conn.commit()
        fid = cur.lastrowid
        conn.close()
        return fid
    except sqlite3.IntegrityError:
        conn.execute(
            "UPDATE whitelist SET username = ?, label = ? WHERE user_id = ?",
            (username or None, label, user_id),
        )
        conn.commit()
        conn.close()
        return user_id

def whitelist_remove(entry_id):
    conn = get_conn()
    conn.execute("DELETE FROM whitelist WHERE id = ?", (entry_id,))
    conn.commit()
    conn.close()

def whitelist_list():
    conn = get_conn()
    rows = conn.execute("SELECT * FROM whitelist ORDER BY created_at DESC").fetchall()
    conn.close()
    return [dict(r) for r in rows]

def whitelist_check(user_id, username=None):
    conn = get_conn()
    count = conn.execute("SELECT COUNT(*) as c FROM whitelist").fetchone()["c"]
    if count == 0:
        conn.close()
        return True
    row = conn.execute(
        "SELECT id FROM whitelist WHERE user_id = ? OR (username IS NOT NULL AND username = ?)",
        (user_id, username),
    ).fetchone()
    conn.close()
    return row is not None

# ─── Settings ────────────────────────────────────────────

def get_setting(key, default=""):
    conn = get_conn()
    row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    conn.close()
    return row["value"] if row else default

def set_setting(key, value):
    conn = get_conn()
    conn.execute(
        "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
        (key, value),
    )
    conn.commit()
    conn.close()

# ─── Reports ────────────────────────────────────────────

def save_report(has_risk, alert_count, ia_analysis, summary, source=""):
    conn = get_conn()
    cur = conn.execute(
        "INSERT INTO reports (date, has_risk, alert_count, ia_analysis, summary, source) VALUES (date('now', 'localtime'), ?, ?, ?, ?, ?)",
        (1 if has_risk else 0, alert_count, ia_analysis, summary, source),
    )
    conn.commit()
    fid = cur.lastrowid
    conn.close()
    return fid

def list_reports(limit=20):
    conn = get_conn()
    rows = conn.execute(
        "SELECT id, date, has_risk, alert_count, source, substr(ia_analysis, 1, 80) as ia_preview, created_at FROM reports ORDER BY created_at DESC LIMIT ?",
        (limit,),
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def get_report(report_id):
    conn = get_conn()
    row = conn.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
    conn.close()
    return dict(row) if row else None

def delete_report(report_id):
    conn = get_conn()
    conn.execute("DELETE FROM reports WHERE id = ?", (report_id,))
    conn.commit()
    conn.close()

def save_sysmon(data):
    conn = get_conn()
    gpu = data.get("gpu", {})
    ram = data.get("ram", {})
    conn.execute(
        "INSERT INTO sysmon_history (cpu, cpu_temp, ram_pct, ram_used, ram_total, gpu_pct, gpu_temp, gpu_vram_used, gpu_vram_total) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (data.get("cpu", 0), data.get("cpu_temp", 0), ram.get("pct", 0), ram.get("used", 0), ram.get("total", 0),
         gpu.get("vram_pct", 0), gpu.get("temp", 0), gpu.get("vram_used", 0), gpu.get("vram_total", 0)),
    )
    conn.commit()
    conn.close()

def list_sysmon_history(limit=30):
    conn = get_conn()
    rows = conn.execute(
        "SELECT * FROM sysmon_history ORDER BY created_at DESC LIMIT ?",
        (limit,),
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def drive_stats():
    conn = get_conn()
    folders = conn.execute("SELECT COUNT(*) as c FROM folders").fetchone()["c"]
    files = conn.execute("SELECT COUNT(*) as c FROM drive_files").fetchone()["c"]
    total_size = conn.execute("SELECT COALESCE(SUM(file_size), 0) as s FROM drive_files").fetchone()["s"]
    conn.close()
    return {"pastas": folders, "arquivos": files, "total_size": total_size}

# ─── RAG ────────────────────────────────────────────────

import json

def save_rag_document(name):
    conn = get_conn()
    cur = conn.execute("INSERT INTO rag_documents (name) VALUES (?)", (name,))
    conn.commit()
    doc_id = cur.lastrowid
    conn.close()
    return doc_id

def get_rag_document(doc_id):
    conn = get_conn()
    row = conn.execute("SELECT * FROM rag_documents WHERE id = ?", (doc_id,)).fetchone()
    conn.close()
    return dict(row) if row else None

def list_rag_documents():
    conn = get_conn()
    rows = conn.execute("""
        SELECT d.*, COUNT(c.id) as chunks
        FROM rag_documents d LEFT JOIN rag_chunks c ON c.doc_id = d.id
        GROUP BY d.id ORDER BY d.created_at DESC
    """).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def delete_rag_document(doc_id):
    conn = get_conn()
    conn.execute("DELETE FROM rag_chunks WHERE doc_id = ?", (doc_id,))
    conn.execute("DELETE FROM rag_documents WHERE id = ?", (doc_id,))
    conn.commit()
    conn.close()

def save_rag_chunk(doc_id, chunk_index, text, embedding):
    conn = get_conn()
    emb_json = json.dumps(embedding)
    conn.execute(
        "INSERT INTO rag_chunks (doc_id, chunk_index, text, embedding) VALUES (?, ?, ?, ?)",
        (doc_id, chunk_index, text, emb_json),
    )
    conn.commit()
    conn.close()

def get_all_rag_chunks():
    conn = get_conn()
    rows = conn.execute("SELECT id, doc_id, chunk_index, text, embedding FROM rag_chunks ORDER BY doc_id, chunk_index").fetchall()
    conn.close()
    return [dict(r) for r in rows]

def load_embedding(emb_json):
    if not emb_json:
        return None
    try:
        return json.loads(emb_json)
    except:
        return None
