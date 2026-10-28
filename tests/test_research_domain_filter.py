"""Isolated tests of web-source collection; no project startup or provider calls."""

import importlib.util
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch


def load_conductor():
    """Load the real module while stubbing unrelated LLM/document imports."""
    modules = {}
    for name in (
        "search", "search.skills", "search.actions",
        "search.utils",
    ):
        modules[name] = ModuleType(name)
        modules[name].__path__ = [str(Path(__file__).resolve().parents[1] / name.replace(".", "/"))]
    imports = {
        "search.actions.agent_creator": {"choose_agent": AsyncMock()},
        "search.actions.query_processing": {
            "get_search_results": AsyncMock(), "plan_research_outline": AsyncMock(),
        },
        "search.actions.utils": {"stream_output": AsyncMock()},
        "search.document": {
            "DocumentLoader": object, "LangChainDocumentLoader": object,
            "OnlineDocumentLoader": object,
        },
        "search.utils.enum": {"ReportSource": object, "ReportType": object},
        "search.utils.logging_config": {"get_json_handler": lambda: None},
    }
    for name, attributes in imports.items():
        modules[name] = ModuleType(name)
        modules[name].__dict__.update(attributes)
    path = Path(__file__).resolve().parents[1] / "search/skills/researcher.py"
    spec = importlib.util.spec_from_file_location("search.skills.researcher", path)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, modules):
        spec.loader.exec_module(module)
    return module


researcher_module = load_conductor()


class DomainFilterTests(unittest.IsolatedAsyncioTestCase):
    def make_conductor(self, results, *, verbose=False):
        calls = []

        class FakeRetriever:
            def __init__(self, query, query_domains=None):
                calls.append((query, query_domains))

            def search(self, max_results):
                return results

        sources = []
        researcher = SimpleNamespace(
            retrievers=[FakeRetriever], cfg=SimpleNamespace(max_search_results_per_query=5),
            verbose=verbose, websocket=None, visited_urls=set(),
            add_research_sources=sources.extend,
        )
        return researcher_module.ResearchConductor(researcher), sources, calls

    async def test_exact_domain_subdomains_and_multiple_domains(self):
        urls = ["https://example.com/a", "https://news.example.com/b", "https://other.org/c"]
        conductor, _, calls = self.make_conductor([{"href": url} for url in urls])
        actual, prefetched = await conductor._search_relevant_source_urls(
            "query", ["example.com", "other.org"]
        )
        self.assertCountEqual(actual, urls)
        self.assertEqual(prefetched, [])
        self.assertEqual(calls, [("query", ["example.com", "other.org"])])

    async def test_rejects_spoofed_hosts_and_domains_in_paths(self):
        urls = [
            "https://fakeexample.com", "https://example.com.evil.org",
            "https://evil.org/example.com", "https://evil.org/?host=example.com",
            "https://example.com@evil.org", "https://evil.org#example.com",
        ]
        conductor, sources, _ = self.make_conductor([{"href": url} for url in urls])
        self.assertEqual(await conductor._search_relevant_source_urls("q", ["example.com"]), ([], []))
        self.assertEqual(sources, [])
        self.assertEqual(conductor.researcher.visited_urls, set())

    async def test_full_content_cannot_bypass_filter(self):
        conductor, sources, _ = self.make_conductor([
            {"href": "https://outside.org", "raw_content": "X" * 150},
            {"url": "https://example.com/full", "raw_content": "Y" * 150},
            {"href": "https://example.com/snippet", "body": "Z" * 150},
        ])
        urls, prefetched = await conductor._search_relevant_source_urls("q", ["example.com"])
        self.assertEqual(urls, ["https://example.com/snippet"])
        self.assertEqual(prefetched, [{"url": "https://example.com/full", "raw_content": "Y" * 150}])
        self.assertEqual(sources, [{"url": "https://example.com/full"}])

    async def test_empty_or_absent_filter_preserves_existing_behavior(self):
        for domains in (None, []):
            with self.subTest(domains=domains):
                conductor, _, _ = self.make_conductor([
                    {"href": "https://outside.org/snippet"},
                    {"url": "https://other.org/full", "raw_content": "X" * 150},
                ])
                urls, prefetched = await conductor._search_relevant_source_urls("q", domains)
                self.assertEqual(urls, ["https://outside.org/snippet"])
                self.assertEqual(prefetched[0]["url"], "https://other.org/full")

    async def test_normalizes_case_trailing_dot_url_port_and_idn(self):
        cases = [
            (" EXAMPLE.COM ", "https://News.Example.Com./article"),
            ("https://EXAMPLE.COM:443/path", "https://example.com:8443/article"),
            ("例子.测试", "https://xn--fsqu00a.xn--0zwm56d/article"),
        ]
        for domain, url in cases:
            with self.subTest(domain=domain):
                conductor, _, _ = self.make_conductor([{"href": url}])
                self.assertEqual(await conductor._search_relevant_source_urls("q", [domain]), ([url], []))

    async def test_invalid_urls_do_not_discard_later_valid_results(self):
        invalid = [None, "", 42, "/relative", "https://[broken", "https://example.com:bad",
                   "javascript:example.com", "ftp://example.com", "https://example.com\\.evil.org"]
        conductor, _, _ = self.make_conductor(
            [{"href": url} for url in invalid] + [{"href": "https://example.com/valid"}]
        )
        self.assertEqual(await conductor._search_relevant_source_urls("q", ["example.com"]),
                         (["https://example.com/valid"], []))

    async def test_invalid_nonempty_filter_does_not_allow_all_domains(self):
        conductor, _, _ = self.make_conductor([{"href": "https://example.com"}])
        self.assertEqual(await conductor._search_relevant_source_urls("q", ["", None, "https://[bad"]), ([], []))

    async def test_empty_filtered_results_emit_notice_without_retry(self):
        conductor, _, calls = self.make_conductor([{"href": "https://outside.org"}], verbose=True)
        with patch.object(researcher_module, "stream_output", new_callable=AsyncMock) as output:
            with self.assertLogs("research", level="WARNING") as logs:
                self.assertEqual(await conductor._search_relevant_source_urls("q", ["example.com"]), ([], []))
            output.assert_awaited_once()
            self.assertEqual(output.call_args.args[1], "domain_filter_no_results")
            self.assertIn("未放宽域名限制", logs.output[0])
        self.assertEqual(len(calls), 1)

    async def test_duplicate_and_visited_urls_still_filtered(self):
        conductor, _, _ = self.make_conductor([
            {"href": "https://example.com/seen"}, {"href": "https://example.com/new"},
            {"href": "https://example.com/new"}, {"href": "https://outside.org"},
        ])
        conductor.researcher.visited_urls.add("https://example.com/seen")
        self.assertEqual(await conductor._search_relevant_source_urls("q", ["example.com"]),
                         (["https://example.com/new"], []))
        self.assertNotIn("https://outside.org", conductor.researcher.visited_urls)


if __name__ == "__main__":
    unittest.main()
