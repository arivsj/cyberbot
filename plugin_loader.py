import os
import importlib
import inspect
import sys

plugins_dir = os.path.join(os.path.dirname(__file__), "plugins")
if plugins_dir not in sys.path:
    sys.path.insert(0, os.path.dirname(__file__))

plugins = {}

class Plugin:
    name = ""
    description = ""
    commands = []
    routes = []
    bot_handlers = []

def load_plugins():
    global plugins
    plugins = {}
    if not os.path.isdir(plugins_dir):
        os.makedirs(plugins_dir, exist_ok=True)
        return
    for fname in sorted(os.listdir(plugins_dir)):
        if not fname.endswith(".py") or fname.startswith("_"):
            continue
        mod_name = fname[:-3]
        full_name = f"plugins.{mod_name}"
        if full_name in sys.modules:
            del sys.modules[full_name]
        try:
            module = importlib.import_module(full_name)
            for name, obj in inspect.getmembers(module):
                if inspect.isclass(obj) and issubclass(obj, Plugin) and obj is not Plugin:
                    inst = obj()
                    plugins[inst.name or mod_name] = inst
        except Exception as e:
            print(f"[plugin] Erro ao carregar {mod_name}: {e}")

def create_plugin(name, code):
    path = os.path.join(plugins_dir, f"{name}.py")
    with open(path, "w") as f:
        f.write(code)
    load_plugins()

def get_plugins_list():
    result = []
    for name, p in plugins.items():
        result.append({
            "name": name,
            "description": p.description,
            "commands": [c[0] for c in getattr(p, "commands", [])],
        })
    return result
