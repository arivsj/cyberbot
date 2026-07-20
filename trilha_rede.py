import os
import subprocess
import shutil
import httpx
import ollama_utils

OLLAMA_BASE = "http://localhost:11434"
MODEL = "gemma4"
OPENCODE = shutil.which("opencode") or "opencode"

def improve_prompt(user_input):
    prompt = (
        "Você é um especialista em transformar ideias em prompts detalhados para IA de código. "
        "Melhore o prompt abaixo, adicionando detalhes técnicos, requisitos de funcionalidades, "
        "tecnologias sugeridas e especificações claras. "
        "Mantenha o prompt em português, organizado e objetivo.\n\n"
        f"Ideia original: {user_input}"
    )
    try:
        r = httpx.post(
            f"{OLLAMA_BASE}/api/chat",
            json={"model": MODEL, "messages": [{"role": "user", "content": prompt}], "options": ollama_utils.get_chat_options(MODEL), "stream": False},
            timeout=120,
        )
        r.raise_for_status()
        return r.json()["message"]["content"]
    except Exception as e:
        return f"Erro: {e}"

def generate(project_path, prompt):
    os.makedirs(project_path, exist_ok=True)
    prompt_file = os.path.join(project_path, "prompt.txt")
    log_file = os.path.join(project_path, "_opencode.log")

    with open(prompt_file, "w") as f:
        f.write(prompt)

    try:
        proc = subprocess.Popen(
            [OPENCODE, "run", prompt, "--dir", project_path, "--auto"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        with open(log_file, "w") as log:
            log.write(f"PID: {proc.pid}\n\n")
            for line in iter(proc.stdout.readline, ""):
                log.write(line)
                log.flush()
            proc.stdout.close()
        proc.wait()
        status = "done" if proc.returncode == 0 else "error"
        with open(log_file, "a") as log:
            log.write(f"\nStatus: {status} (exit code: {proc.returncode})\n")
        return status
    except Exception as e:
        with open(log_file, "a") as log:
            log.write(f"\nERRO: {e}\n")
        return "error"

def get_logs(project_path, n=10):
    log_file = os.path.join(project_path, "_opencode.log")
    if not os.path.exists(log_file):
        return []
    with open(log_file) as f:
        lines = f.readlines()
    return lines[-n:]
