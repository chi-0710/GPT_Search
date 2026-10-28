"""Isolated shared-library tests: real application code, fake external services."""

import asyncio
from contextlib import asynccontextmanager
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
import uuid

import pytest
from sqlalchemy.dialects import postgresql
import sqlalchemy.orm  # Load before patch.dict so the real ORM cache is retained.

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/fixtures/shared_library"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def module(name, **attributes):
    value = ModuleType(name)
    value.__dict__.update(attributes)
    return value


def isolated_modules():
    packages = {}
    for name in ("backend", "backend.server", "search", "search.document",
                 "search.memory", "search.utils", "langchain_community"):
        packages[name] = module(name, __path__=[str(ROOT / name.replace(".", "/"))])
    loaders = {name: MagicMock(name=name) for name in (
        "PyMuPDFLoader", "TextLoader", "UnstructuredCSVLoader", "UnstructuredExcelLoader",
        "UnstructuredMarkdownLoader", "UnstructuredPowerPointLoader", "UnstructuredWordDocumentLoader", "BSHTMLLoader")}
    packages["langchain_community.document_loaders"] = module("langchain_community.document_loaders", **loaders)
    packages["langchain_text_splitters"] = module("langchain_text_splitters", RecursiveCharacterTextSplitter=MagicMock())
    packages["search.memory.embeddings"] = module("search.memory.embeddings", Memory=MagicMock())
    packages["backend.server.database"] = module("backend.server.database", StorageConflict=type("StorageConflict", (RuntimeError,), {}),
                                                  read_session=MagicMock(), transaction=MagicMock())
    with patch.dict(sys.modules, packages):
        evidence = load("search.utils.evidence", "search/utils/evidence.py")
        loader = load("search.document.document", "search/document/document.py")
        models = load("backend.server.db_models", "backend/server/db_models.py")
        store = load("backend.server.postgres_store", "backend/server/postgres_store.py")
        service = load("backend.server.document_service", "backend/server/document_service.py")
    return evidence, loader, models, store, service


EVIDENCE, LOADER, MODELS, STORE, SERVICE = isolated_modules()
A, B, PROFILE, RUN = [str(uuid.uuid4()) for _ in range(4)]


def ready_documents():
    return [{"id": A, "status": "ready", "embedding_profile_id": PROFILE},
            {"id": B, "status": "ready", "embedding_profile_id": PROFILE}]


def test_selected_range_and_all_are_frozen():
    docs = ready_documents()
    assert SERVICE.select_document_ids(docs, PROFILE, "selected", [A, A], "hybrid") == [A]
    frozen = SERVICE.select_document_ids(docs, PROFILE, "all", [], "hybrid")
    docs.append({"id": "later", "status": "ready", "embedding_profile_id": PROFILE})
    assert "later" not in frozen
    assert set(frozen) == {A, B}


@pytest.mark.parametrize("scope,ids,status,profile", [
    ("selected", [], "ready", PROFILE), ("selected", ["missing"], "ready", PROFILE),
    ("selected", [A], "processing", PROFILE), ("selected", [A], "failed", PROFILE),
    ("selected", [A], "ready", "old-model"), ("unknown", [A], "ready", PROFILE),
])
def test_invalid_selection_never_falls_back(scope, ids, status, profile):
    with pytest.raises(ValueError):
        SERVICE.select_document_ids([{"id": A, "status": status, "embedding_profile_id": profile}],
                                    PROFILE, scope, ids, "hybrid")


def test_empty_library_local_fails_hybrid_allowed():
    with pytest.raises(ValueError, match="没有可检索"):
        SERVICE.select_document_ids([], PROFILE, "all", [], "local")
    assert SERVICE.select_document_ids([], PROFILE, "all", [], "hybrid") == []


def test_web_skips_library_and_multi_agents_resolves_files():
    store = SimpleNamespace(get_or_create_embedding_profile=AsyncMock())
    assert asyncio.run(SERVICE.resolve_document_scope(store, {"report_source": "web"})) == []
    store.get_or_create_embedding_profile.assert_not_called()
    store.list_documents = AsyncMock(return_value=ready_documents())
    store.get_or_create_embedding_profile = AsyncMock(return_value={"id": PROFILE})
    assert asyncio.run(SERVICE.resolve_document_scope(store, {"report_source": "hybrid", "report_type": "multi_agents"})) == sorted([A, B])


def test_scope_resolution_defaults_and_selected_ids():
    store = SimpleNamespace(get_or_create_embedding_profile=AsyncMock(return_value={"id": PROFILE}),
                            list_documents=AsyncMock(return_value=ready_documents()))
    assert set(asyncio.run(SERVICE.resolve_document_scope(store, {"report_source": "local"}))) == {A, B}
    assert asyncio.run(SERVICE.resolve_document_scope(store, {"report_source": "hybrid", "document_scope": "selected", "document_ids": [B]})) == [B]


def test_pdf_loader_is_lazy_and_preserves_page_metadata():
    pages = [SimpleNamespace(page_content="ALPHA text", metadata={"source": "paper_a.pdf", "page": 1})]
    selected = MagicMock(return_value=SimpleNamespace(load=lambda: pages))
    unused = MagicMock(side_effect=AssertionError("An unrelated parser was initialized"))
    with patch.object(LOADER, "PyMuPDFLoader", selected), patch.object(LOADER, "UnstructuredWordDocumentLoader", unused):
        docs = asyncio.run(LOADER.DocumentLoader([str(FIXTURES / "paper_a.pdf")]).load())
    assert docs[0]["metadata"]["page_number"] == 2
    assert docs[0]["raw_content"] == "ALPHA text"
    unused.assert_not_called()


def test_empty_pdf_reports_ocr_limit():
    with patch.object(LOADER, "PyMuPDFLoader", return_value=SimpleNamespace(load=lambda: [])):
        with pytest.raises(ValueError, match="OCR"):
            asyncio.run(LOADER.DocumentLoader([str(FIXTURES / "paper_a.pdf")]).load())


def test_parser_failure_preserves_cause_and_rejects_unsupported_format():
    with patch.object(LOADER, "PyMuPDFLoader", side_effect=RuntimeError("missing parser dependency")):
        with pytest.raises(ValueError, match="missing parser dependency"):
            asyncio.run(LOADER.DocumentLoader([])._load_document("paper.pdf", "pdf"))
    with pytest.raises(ValueError, match="epub"):
        asyncio.run(LOADER.DocumentLoader([])._load_document("book.epub", "epub"))


def test_prepare_index_retains_page_and_does_not_expose_temp_path():
    stored = SimpleNamespace(filename="paper_a.pdf", content=(FIXTURES / "paper_a.pdf").read_bytes())
    fake_store = SimpleNamespace(get_document=AsyncMock(return_value=stored))
    pages = [{"raw_content": "alpha text", "url": "paper_a.pdf",
              "metadata": {"page_number": 2, "source": "/temporary/file.pdf"}}]
    splitter = SimpleNamespace(split_text=lambda value: [value])
    embedding = SimpleNamespace(aembed_documents=AsyncMock(return_value=[[0.1, 0.2]]))
    with patch.object(SERVICE, "DocumentLoader", return_value=SimpleNamespace(load=AsyncMock(return_value=pages))), \
         patch.object(SERVICE, "RecursiveCharacterTextSplitter", return_value=splitter), \
         patch.object(SERVICE, "Memory", return_value=SimpleNamespace(get_embeddings=lambda: embedding)), \
         patch.dict("os.environ", {"EMBEDDING_DIMENSION": "2"}):
        result = asyncio.run(SERVICE.prepare_document_index(fake_store, A))
    metadata = result["chunks"][0]["metadata"]
    assert metadata["page_number"] == 2
    assert "source" not in metadata
    assert "/temporary" not in str(metadata)


def test_retriever_passes_exact_scope_and_returns_structured_evidence():
    match = {"document_id": A, "chunk_id": "4", "page": 2, "raw_content": "alpha"}
    store = SimpleNamespace(get_or_create_embedding_profile=AsyncMock(return_value={"dimensions": 2}),
                            search_document_chunks=AsyncMock(return_value=[match]))
    embedding = SimpleNamespace(aembed_query=AsyncMock(return_value=[0.1, 0.2]))
    with patch.object(SERVICE, "Memory", return_value=SimpleNamespace(get_embeddings=lambda: embedding)):
        result = asyncio.run(SERVICE.PostgresDocumentRetriever(store, [A])("alpha"))
    assert result == [match]
    assert store.search_document_chunks.call_args.kwargs["document_ids"] == [A]
    store.search_document_chunks.reset_mock()
    assert asyncio.run(SERVICE.PostgresDocumentRetriever(store, [])("alpha")) == []
    store.search_document_chunks.assert_not_called()


def sample_document(content="The ALPHA window is 17 days."):
    return {"document_id": A, "chunk_id": "4", "page": 2, "title": "paper_a.pdf", "raw_content": content}


def test_evidence_both_sources_and_real_citation_links():
    registry = EVIDENCE.EvidenceRegistry(RUN)
    web = json.loads((FIXTURES / "web_results.json").read_text())
    web_ids = registry.register(web, "web")
    context = registry.context([sample_document()], f"[E{web_ids[0]}]")
    assert "17 days" in context and "21 days" in context
    snapshots = registry.snapshots()
    assert {item["source_type"] for item in snapshots} == {"web", "document"}
    doc_number = next(item["number"] for item in snapshots if item["source_type"] == "document")
    report = registry.render_report(f"ALPHA [E{doc_number}] missing [E999]")
    assert f"/api/research-runs/{RUN}/evidence/{doc_number}/view" in report
    assert "第 2 页" in report and "引用不可核验" in report
    assert "E999" not in report


@pytest.mark.parametrize("url,expected", [
    ("https://example.org/article?q=alpha&lang=en#part", "https://example.org/article?q=alpha&lang=en#part"),
    ("https://example.org/a_(b)?q=two words&x=%20", "https://example.org/a_%28b%29?q=two%20words&x=%20"),
])
def test_web_citation_links_to_registered_original(url, expected):
    registry = EVIDENCE.EvidenceRegistry(RUN)
    number = registry.register([{"url": url, "title": "Original", "raw_content": "Evidence"}], "web")[0]
    registry.context([], f"[E{number}]")
    snapshots = registry.snapshots()
    report = registry.render_report(f"Result [E{number}](https://fake.org) unknown [E999]")
    assert f"]({expected})" in report
    assert "/evidence/" not in report and "fake.org" not in report
    assert "引用不可核验" in report
    assert registry.snapshots() == snapshots


@pytest.mark.parametrize("source_type", ["web", "document", "mcp"])
def test_citation_titles_escape_table_delimiters(source_type):
    registry = EVIDENCE.EvidenceRegistry(RUN)
    item = {"url": "https://example.org/article", "title": "Title | Publisher", "raw_content": "Evidence"}
    if source_type == "document":
        registry.context([{**sample_document(), **item}], [])
    else:
        number = registry.register([item], source_type)[0]
        registry.context([], f"[E{number}]")
    report = registry.render_report("| Topic | Evidence |\n| --- | --- |\n| Example | [E1] |")
    assert r"Title \| Publisher" in report
    assert "Title | Publisher" not in report
    assert registry.snapshots()[0]["title"] == "Title | Publisher"
    target = "https://example.org/article" if source_type == "web" else f"/api/research-runs/{RUN}/evidence/1/view"
    assert f"]({target})" in report


def test_mcp_citation_keeps_snapshot_link():
    registry = EVIDENCE.EvidenceRegistry(RUN)
    number = registry.register([{"url": "tool:result", "raw_content": "Tool evidence"}], "mcp")[0]
    registry.context([], f"[E{number}]")
    assert f"/api/research-runs/{RUN}/evidence/{number}/view" in registry.render_report(f"[E{number}]")


def test_evidence_budget_dedupe_and_absent_source_transfer():
    registry = EVIDENCE.EvidenceRegistry(RUN)
    web_id = registry.register([{"url": "https://example.org", "raw_content": "W" * 50}], "web")[0]
    with patch.dict("os.environ", {"DOCUMENT_CONTEXT_CHAR_BUDGET": "10", "WEB_CONTEXT_CHAR_BUDGET": "12"}):
        registry.context([sample_document("D" * 50)] * 2, f"[E{web_id}] [E{web_id}]")
    snapshots = registry.snapshots()
    assert sorted(len(item["content"]) for item in snapshots) == [10, 12]
    registry = EVIDENCE.EvidenceRegistry(RUN)
    with patch.dict("os.environ", {"DOCUMENT_CONTEXT_CHAR_BUDGET": "10", "WEB_CONTEXT_CHAR_BUDGET": "12"}):
        registry.context([sample_document("D" * 50)], [])
    assert len(registry.snapshots()[0]["content"]) == 22


def test_no_evidence_refuses_report_and_does_not_use_unselected_file():
    registry = EVIDENCE.EvidenceRegistry(RUN)
    registry.register([sample_document("BETA private data")], "document")
    with pytest.raises(ValueError, match="资料不足"):
        registry.context([], [])
    assert registry.snapshots() == []
    assert "BETA" not in registry.render_report("Unknown [E1]")


def test_shared_registry_keeps_ids_across_sections():
    registry = EVIDENCE.EvidenceRegistry(RUN)
    first = registry.context([sample_document()], [])
    second = registry.context([sample_document()], [])
    assert first == second
    assert len(registry.snapshots()) == 1


@asynccontextmanager
async def session_context(session):
    yield session


def test_sql_restricts_ids_profile_status_and_preserves_metadata():
    session = SimpleNamespace(scalar=AsyncMock(return_value=SimpleNamespace(id=uuid.UUID(PROFILE), dimensions=2)),
                              execute=AsyncMock(return_value=SimpleNamespace(mappings=lambda: [
                                  {"document_id": uuid.UUID(A), "chunk_id": 4, "content": "alpha", "filename": "paper_a.pdf",
                                   "metadata": {"page_number": 2}, "similarity": 0.9}])))
    with patch.object(STORE, "read_session", lambda: session_context(session)):
        result = asyncio.run(STORE.PostgresStore().search_document_chunks([0.1, 0.2], document_ids=[A]))
    sql, params = session.execute.call_args.args
    assert "dc.document_id = ANY" in str(sql)
    assert "d.status = 'ready'" in str(sql) and "d.embedding_profile_id = :profile_id" in str(sql)
    assert params["document_ids"] == [A] and params["all_documents"] is False
    assert result[0]["document_id"] == A and result[0]["page"] == 2


@pytest.mark.parametrize("inserted", [None, uuid.UUID(A)])
def test_upload_uses_atomic_conflict_handling_without_overwrite(inserted):
    document = SimpleNamespace(id=uuid.UUID(A), filename="paper.pdf", mime_type="application/pdf", byte_size=3,
        sha256="hash", status="processing", error=None, created_at=None, updated_at=None, embedding_profile_id=None)
    session = SimpleNamespace(scalar=AsyncMock(side_effect=[inserted, document]))
    with patch.object(STORE, "transaction", lambda: session_context(session)):
        result = asyncio.run(STORE.PostgresStore().put_document_blob(filename="paper.pdf", mime_type="application/pdf", content=b"abc"))
    sql = str(session.scalar.call_args_list[0].args[0].compile(dialect=postgresql.dialect()))
    assert "ON CONFLICT ON CONSTRAINT uq_documents_filename_sha256 DO NOTHING" in sql
    assert "UPDATE" not in sql
    assert result["reused"] is (inserted is None)


def test_stored_evidence_survives_original_document_deletion():
    snapshot = {"number": 1, "source_type": "document", "document_id": A, "content": "saved alpha"}
    store = STORE.PostgresStore()
    store._run_and_public_id = AsyncMock(return_value=(SimpleNamespace(id=uuid.UUID(RUN)), "report-id"))
    session = SimpleNamespace(scalars=AsyncMock(side_effect=[SimpleNamespace(all=lambda: [snapshot]), SimpleNamespace(all=lambda: [])]))
    with patch.object(STORE, "read_session", lambda: session_context(session)):
        results = asyncio.run(store.list_evidence(RUN))
    assert results[0]["content"] == "saved alpha"
    assert results[0]["original_available"] is False


def test_pdf_fixture_text_and_page_count():
    pypdf = pytest.importorskip("pypdf")
    for filename, phrase in (("paper_a.pdf", "17-day"), ("paper_b.pdf", "83 units")):
        reader = pypdf.PdfReader(FIXTURES / filename)
        assert len(reader.pages) == 2
        assert phrase in reader.pages[1].extract_text()


def test_frozen_initial_migration_and_additive_upgrade():
    # Alembic executes migration modules without registering them in sys.modules.
    # Keep annotations evaluable in that loader as well as ordinary imports.
    operations = MagicMock()
    with patch.dict(sys.modules, {"alembic": module("alembic", op=operations)}):
        spec = importlib.util.spec_from_file_location("frozen_v1", ROOT / "alembic/versions/0001_postgres_persistence.py")
        initial = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(initial)
        upgrade = load("shared_v2", "alembic/versions/0002_shared_library.py")
    assert "research_evidence" not in initial.Base.metadata.tables
    assert initial.Base.metadata.tables["documents"].c.filename.unique is True
    assert MODELS.StoredDocument.__table__.c.filename.unique is not True
    upgrade.upgrade()
    operations.create_unique_constraint.assert_called_once_with("uq_documents_filename_sha256", "documents", ["filename", "sha256"])
    assert operations.create_table.call_args.args[0] == "research_evidence"
    operations.drop_table.assert_not_called()
    operations.get_bind.return_value.scalar.return_value = True
    with pytest.raises(RuntimeError, match="share a filename"):
        upgrade.downgrade()
    operations.drop_table.assert_not_called()
