"""Generate a credential-free migration inventory from the Python source tree."""

import ast
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def providers(path):
    tree = ast.parse((ROOT / path).read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "_SUPPORTED_PROVIDERS"
            for target in node.targets
        ):
            return sorted(ast.literal_eval(node.value))
    raise ValueError(f"Provider registry missing: {path}")


def directories(path):
    return sorted(p.name for p in (ROOT / path).iterdir()
                  if p.is_dir() and not p.name.startswith("_"))


def tree(path):
    return ast.parse((ROOT / path).read_text(encoding="utf-8"))


def cli_options():
    return sorted({arg.value for node in ast.walk(tree("cli.py"))
                   if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                   and node.func.attr == "add_argument"
                   for arg in node.args if isinstance(arg, ast.Constant) and isinstance(arg.value, str)})


def dictionary_keys(path, name):
    for node in ast.walk(tree(path)):
        if isinstance(node, ast.Assign):
            targets = node.targets
        elif isinstance(node, ast.AnnAssign):
            targets = [node.target]
        else:
            continue
        if any(isinstance(target, ast.Name) and target.id == name for target in targets):
            if not isinstance(node.value, ast.Dict):
                raise ValueError(f"Expected literal dictionary: {name}")
            return sorted(ast.literal_eval(key) for key in node.value.keys)
    raise ValueError(f"Dictionary missing: {name}")


def inventory():
    tree = ast.parse((ROOT / "backend/server/app.py").read_text(encoding="utf-8"))
    routes = []
    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for decorator in node.decorator_list:
            if (isinstance(decorator, ast.Call)
                    and isinstance(decorator.func, ast.Attribute)
                    and isinstance(decorator.func.value, ast.Name)
                    and decorator.func.value.id == "app"
                    and decorator.func.attr in {"get", "post", "put", "delete", "websocket"}):
                routes.append({"method": decorator.func.attr.upper(),
                               "path": ast.literal_eval(decorator.args[0]),
                               "handler": node.name})
    return {
        "schema_version": 1,
        "routes": routes,
        "chat_providers": providers("search/llm_provider/generic/base.py"),
        "embedding_providers": providers("search/memory/embeddings.py"),
        "retrievers": directories("search/retrievers"),
        "scrapers": directories("search/scraper"),
        "document_formats": dictionary_keys("search/document/document.py", "loader_dict"),
        "configuration_keys": dictionary_keys("search/config/variables/default.py", "DEFAULT_CONFIG"),
        "cli_arguments": cli_options(),
        "image_providers": ["google", "modelslab"],
        "websocket_commands": ["ping", "start", "chat", "human_feedback"],
        "mcp_server": "External upstream repository only; no executable server in mcp-server/.",
        "completion_rule": "Inventory presence is not implementation or live verification.",
    }


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    target = ROOT / "backend-java/migration-inventory.json"
    rendered = json.dumps(inventory(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not target.exists() or target.read_text(encoding="utf-8") != rendered:
            raise SystemExit("Migration inventory is stale; regenerate it before changing scope.")
        print("Migration inventory matches source registries and routes.")
    else:
        target.write_text(rendered, encoding="utf-8")
        print(f"Generated {target.name}")
