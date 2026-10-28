"""
Provides a command line interface for the Research class.

Usage:

```shell
python cli.py "<query>" --report_type <report_type> --tone <tone> --query_domains <foo.com,bar.com>
```

"""
import argparse
import asyncio
from argparse import RawTextHelpFormatter

from dotenv import load_dotenv

from backend.server.multi_agent_runner import run_multi_agent_task
from search.utils.report_modes import normalize_report_mode
from search.utils.enum import Tone

# =============================================================================
# CLI
# =============================================================================

cli = argparse.ArgumentParser(
    description="Generate a research report.",
    # Enables the use of newlines in the help message
    formatter_class=RawTextHelpFormatter)

# =====================================
# Arg: Query
# =====================================

cli.add_argument(
    # Position 0 argument
    "query",
    type=str,
    help="The query to conduct research on.")

# =====================================
# Arg: Report Type
# =====================================

cli.add_argument(
    "--report_type", type=normalize_report_mode, default="multi_agents",
    help="多智能体报告（旧模式名称自动转换为 multi_agents）")

# =====================================
# Arg: Tone
# =====================================

cli.add_argument(
    "--tone",
    type=str,
    help="The tone of the report (optional).",
    choices=["objective", "formal", "analytical", "persuasive", "informative",
            "explanatory", "descriptive", "critical", "comparative", "speculative",
            "reflective", "narrative", "humorous", "optimistic", "pessimistic"],
    default="objective"
)

# =====================================
# Arg: Encoding
# =====================================

cli.add_argument(
    "--encoding",
    type=str,
    help="The encoding to use for the output file (default: utf-8).",
    default="utf-8"
)

# =====================================
# Arg: Query Domains
# =====================================

cli.add_argument(
    "--query_domains",
    type=str,
    help="A comma-separated list of domains to search for the query.",
    default=""
)

# =====================================
# Arg: Report Source
# =====================================

cli.add_argument(
    "--report_source",
    type=str,
    help="The source of information for the report.",
    choices=["web", "local", "hybrid"],
    default="web"
)

# =====================================
# Arg: Output Format Flags
# =====================================

cli.add_argument(
    "--no-pdf",
    action="store_true",
    help="Skip PDF generation (generate markdown and DOCX only)."
)

cli.add_argument(
    "--no-docx",
    action="store_true",
    help="Skip DOCX generation (generate markdown and PDF only)."
)

async def main(args):
    """
    Conduct research on the given query, generate the report, and write
    it as a markdown file to the output directory.
    """
    query_domains = args.query_domains.split(",") if args.query_domains else []

    from backend.server.postgres_store import PostgresStore
    from backend.server.document_service import resolve_document_scope, PostgresDocumentRetriever
    from backend.server.artifact_service import persist_report_artifacts
    from backend.server.database import dispose_engine
    store = PostgresStore()
    args.report_type = normalize_report_mode(args.report_type)
    document_ids = await resolve_document_scope(store, vars(args))
    run = await store.create_run(query=args.query, report_type=args.report_type,
                                 report_source=args.report_source, tone=args.tone.capitalize(),
                                 request_options={**vars(args), "resolved_document_ids": document_ids})
    async def artifact_sink(*, content, filename, mime_type, research_id=None):
        metadata = await store.put_artifact(content=content, filename=filename,
            kind="image", mime_type=mime_type, report_public_id=run["research_id"], run_id=run["run_id"])
        return metadata["url"]
    try:
        result = await run_multi_agent_task(
            args.query, report_type=args.report_type, report_source=args.report_source,
            tone=Tone[args.tone.capitalize()], query_domains=query_domains,
            run_id=run["run_id"], artifact_sink=artifact_sink,
            document_retriever=PostgresDocumentRetriever(store, document_ids=document_ids))
        await store.save_evidence(run["run_id"], result.evidence)
        paths = await persist_report_artifacts(store, report_public_id=run["research_id"],
            run_id=run["run_id"], markdown=result.report, legacy_stem=run["research_id"],
            include_pdf=not args.no_pdf, include_docx=not args.no_docx)
        await store.complete_run(run["run_id"], answer=result.report, costs=result.costs, sources=result.sources)
        print(paths)
    except BaseException as exc:
        await store.fail_run(run["run_id"], str(exc) or "研究任务已取消")
        raise
    finally:
        await dispose_engine()
if __name__ == "__main__":
    load_dotenv()
    args = cli.parse_args()
    asyncio.run(main(args))
