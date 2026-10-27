"""Seed synthetic compatibility data ONLY in the isolated parity database."""

import asyncio
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path.cwd() / "backend"))


async def main():
    if not os.environ.get("DATABASE_URL", "").endswith("/researchrunner_parity_test"):
        raise RuntimeError("Refusing to seed anything except researchrunner_parity_test")
    from server.postgres_store import PostgresStore
    from server.database import dispose_engine

    store = PostgresStore()
    try:
        if await store.get_report("java-parity-sample"):
            print("Synthetic fixture already exists")
            return
        run = await store.create_run(query="Java migration compatibility fixture", report_type="multi_agents",
                                     report_source="hybrid", tone="Objective", public_id="java-parity-sample")
        document = await store.put_document_blob(filename="中文资料.pdf", mime_type="application/pdf",
                                                 content=b"%PDF-1.7\nsynthetic fixture\n")
        artifact = await store.put_artifact(content=b"synthetic docx bytes\x00\x01", filename="中文报告.docx",
                                           kind="docx", mime_type="application/octet-stream",
                                           report_public_id=run["research_id"], run_id=run["run_id"],
                                           legacy_path="outputs/parity.docx")
        await store.append_event(run["run_id"], {"type": "path", "output": {"docx": artifact["url"]}})
        await store.save_evidence(run["run_id"], [
            {"number": 1, "source_type": "local", "document_id": document["id"],
             "title": "中文资料.pdf", "content": "引用片段 <script>不得执行</script>", "page": 1},
            {"number": 2, "source_type": "web", "url": "https://example.com", "title": "Example", "content": "fixture"},
            {"number": 3, "source_type": "local", "document_id": "00000000-0000-0000-0000-000000000999",
             "title": "已删除.txt", "content": "历史证据保留"},
        ])
        await store.complete_run(run["run_id"], answer="# 中文报告\n兼容性验证。", costs={"total": 0.125},
                                 sources=[{"url": "https://example.com", "title": "Example", "raw_content": "fixture"}])
        await store.add_chat_message(run["research_id"], {"role": "user", "content": "引用在哪里？", "timestamp": 100})
        await store.add_chat_message(run["research_id"], {"role": "assistant", "content": "查看证据快照。", "metadata": {"source": "report"}})
        print("Synthetic parity fixture created; no model or search calls made")
    finally:
        await dispose_engine()


if __name__ == "__main__":
    asyncio.run(main())
