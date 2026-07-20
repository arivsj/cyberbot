import httpx
import sysmon

OLLAMA_BASE = "http://localhost:11434"
DEFAULT_MAX_CTX = 131072
CTX_CACHE = {}

def get_model_info(model_name):
    try:
        r = httpx.post(f"{OLLAMA_BASE}/api/show", json={"model": model_name}, timeout=10)
        if r.status_code == 200:
            return r.json()
    except:
        pass
    return {}

def get_model_max_context(model_name):
    key = f"max_ctx|{model_name}"
    if key in CTX_CACHE:
        return CTX_CACHE[key]

    info = get_model_info(model_name)
    model_info = info.get("model_info", {})
    for k, v in model_info.items():
        if "context_length" in k.lower():
            try:
                ctx = int(v)
                CTX_CACHE[key] = ctx
                return ctx
            except:
                pass

    modelfile = info.get("modelfile", "")
    for line in modelfile.split("\n"):
        if "num_ctx" in line.lower():
            parts = line.split()
            if len(parts) >= 2:
                try:
                    ctx = int(parts[-1])
                    CTX_CACHE[key] = ctx
                    return ctx
                except:
                    pass

    CTX_CACHE[key] = DEFAULT_MAX_CTX
    return DEFAULT_MAX_CTX

def get_optimal_context(model_name):
    key = f"opt_ctx|{model_name}"
    if key in CTX_CACHE:
        return CTX_CACHE[key]

    max_ctx = get_model_max_context(model_name)
    UPPER = 262000
    LOWER = 4096

    gpu = sysmon.get_gpu()
    if not gpu.get("present"):
        ctx = min(max_ctx, UPPER)
        CTX_CACHE[key] = ctx
        return ctx

    vram_total = gpu.get("vram_total", 0)
    vram_used = gpu.get("vram_used", 0)
    vram_free = max(0, vram_total - vram_used)

    buffer_mb = 1024
    avail_mb = max(0, vram_free - buffer_mb)

    tokens_per_mb = 25
    ctx = max(LOWER, min(max_ctx, int(avail_mb * tokens_per_mb), UPPER))
    CTX_CACHE[key] = ctx
    return ctx

def get_chat_options(model_name):
    ctx = get_optimal_context(model_name)
    return {"num_ctx": ctx}

def get_context_info(model_name):
    max_ctx = get_model_max_context(model_name)
    opt_ctx = get_optimal_context(model_name)
    return {"model": model_name, "max_context": max_ctx, "optimal_context": opt_ctx}

def clear_cache():
    CTX_CACHE.clear()
