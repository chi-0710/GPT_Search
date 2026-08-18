# Repository Guidelines

## Project Structure & Module Organization

`gpt_researcher/` contains the core Python package: orchestration, retrievers, scrapers, prompts, skills, and utilities. Root `main.py` exposes the FastAPI application; `backend/` holds chat and report support code. Interfaces live in `frontend/` and `frontend/nextjs/`; place assets in their existing `static/` or `public/` directories. Tests are under `tests/`, including focused `backend/` and `skills/` suites. The Docusaurus site is in `docs/`. Experimental implementations live in `multi_agents/` and `deep_agents/`; infrastructure lives in `terraform/`.

## Build, Test, and Development Commands

- `python -m pip install -r requirements.txt` installs Python dependencies (Python 3.11+).
- `python -m uvicorn main:app --reload` starts the local application with reload.
- `python -m pytest tests/` runs pytest; pass a file path or `-k name` to focus.
- `docker compose up --build` builds and starts the full local stack.
- In `frontend/nextjs/`, run `npm install --legacy-peer-deps`, then `npm run dev`; validate with `npm run lint` and `npm run build`.
- In `docs/`, run `yarn install` and `yarn start` to preview documentation.

## Coding Style & Naming Conventions

Use four-space indentation and PEP 8 naming for Python: `snake_case` functions/modules and `PascalCase` classes. Add type hints and concise public-API docstrings. No repository-wide Python formatter is configured, so match nearby code. TypeScript uses strict mode; name components `PascalCase`, helpers `camelCase`, and format with Prettier. ESLint extends `next/core-web-vitals`.

## Testing Guidelines

Pytest discovers `tests/test_*.py`; name tests `test_<behavior>`. Async tests use strict mode, so mark them explicitly and isolate fixtures. Add regression tests beside the closest suite. No coverage percentage is enforced, but changes should cover success and failure paths. Mock network and paid-provider calls unless credentials are intentionally required.

## Commit & Pull Request Guidelines

`CONTRIBUTING.md` requests concise, informative commits; use an imperative subject such as `Fix malformed Brave results`. Branch from `master` and keep commits focused. Pull requests should explain the problem and solution, link issues, list tests run, and call out configuration changes. Include screenshots for UI changes and update docs when setup or behavior changes.

## Security & Configuration

Copy `.env.example` to `.env` and supply only needed provider keys. Never commit secrets or local environment files. Add new options to the example file with safe defaults and documentation.
