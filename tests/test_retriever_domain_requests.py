"""Request-level tests using real retrievers, without app startup or network I/O."""

import importlib
from pathlib import Path
import sys
from types import ModuleType
from unittest.mock import MagicMock, patch

import pytest
import requests


def load_modules():
    root = Path(__file__).resolve().parents[1]
    packages = {}
    for name in ("search", "search.retrievers", "search.utils"):
        packages[name] = ModuleType(name)
        packages[name].__path__ = [str(root / name.replace(".", "/"))]
    with patch.dict(sys.modules, packages):
        modules = {
            name: importlib.import_module(f"search.retrievers.{name}.{name}")
            for name in ("brave", "bing", "duckduckgo", "searx")
        }
        utility = importlib.import_module("search.utils.domain_filter")
    return modules, utility


MODULES, UTILITY = load_modules()
CLASSES = {"brave": "BraveSearch", "bing": "BingSearch", "duckduckgo": "Duckduckgo", "searx": "SearxSearch"}


@pytest.fixture(params=CLASSES)
def retriever(request):
    name = request.param
    module = MODULES[name]
    ddgs = ModuleType("ddgs")
    ddgs.DDGS = MagicMock()
    with patch.dict("os.environ", {"BRAVE_API_KEY": "test", "BING_API_KEY": "test", "SEARX_URL": "https://search.test"}):
        with patch.dict(sys.modules, {"ddgs": ddgs}):
            if name == "duckduckgo":
                with patch.object(module, "check_pkg"):
                    instance = module.Duckduckgo("cats OR dogs")
            else:
                instance = getattr(module, CLASSES[name])("cats OR dogs")
    if name == "duckduckgo":
        client = instance.ddg.text
        client.return_value = [{"href": "https://example.com/a", "body": "snippet"}]
        yield name, instance, client
    else:
        response = MagicMock()
        response.json.return_value = {
            "web": {"results": [{"url": "https://example.com/a", "description": "snippet"}]},
            "results": [{"url": "https://example.com/a", "content": "snippet"}],
        }
        response.text = '{"webPages":{"value":[{"url":"https://example.com/a","snippet":"snippet"}]}}'
        with patch.object(module.requests, "get", return_value=response) as client:
            yield name, instance, client


def query_values(name, client):
    if name == "duckduckgo":
        return [call.args[0] for call in client.call_args_list]
    return [call.kwargs["params"]["q"] for call in client.call_args_list]


@pytest.mark.parametrize("domains", [None, []])
def test_no_restriction_preserves_query_and_response(retriever, domains):
    name, instance, client = retriever
    instance.query_domains = domains
    result = instance.search(max_results=2)
    assert query_values(name, client) == ["cats OR dogs"]
    assert result[0]["href"] == "https://example.com/a"
    assert result[0]["body"] == "snippet"


def test_single_domain_is_sent_in_request(retriever):
    name, instance, client = retriever
    instance.query_domains = ["example.com"]
    assert instance.search(max_results=2)
    expected = "(cats OR dogs) (site:example.com)" if name in ("brave", "bing") else "cats OR dogs site:example.com"
    assert query_values(name, client) == [expected]
    assert instance.query == "cats OR dogs"


def test_multiple_domains_use_union_or_separate_requests(retriever):
    name, instance, client = retriever
    instance.query_domains = ["example.com", "other.org"]
    result = instance.search(max_results=2)
    if name in ("brave", "bing"):
        assert query_values(name, client) == ["(cats OR dogs) (site:example.com OR site:other.org)"]
    else:
        assert query_values(name, client) == ["cats OR dogs site:example.com", "cats OR dogs site:other.org"]
        assert len(result) == 1  # Duplicate URLs across requests are merged.


def test_domain_normalization_deduplicates_before_request(retriever):
    name, instance, client = retriever
    instance.query_domains = [" HTTPS://Example.COM.:443/path ", "example.com"]
    instance.search()
    queries = query_values(name, client)
    assert len(queries) == 1
    assert queries[0].count("site:example.com") == 1
    assert "/path" not in queries[0]


def test_invalid_domains_never_send_unrestricted_request(retriever):
    _, instance, client = retriever
    instance.query_domains = [None, "", "bad host OR site:evil.org", "https://[bad"]
    assert instance.search() == []
    client.assert_not_called()


def test_request_failure_keeps_original_error_contract(retriever):
    name, instance, client = retriever
    instance.query_domains = ["example.com"]
    client.side_effect = requests.RequestException("offline test")
    if name == "bing":
        with pytest.raises(requests.RequestException):
            instance.search()
    else:
        assert instance.search() == []
    assert len(query_values(name, client)) == 1


@pytest.mark.parametrize("retriever", ["duckduckgo", "searx"], indirect=True)
def test_split_requests_continue_after_one_domain_fails(retriever):
    name, instance, client = retriever
    success = client.return_value
    client.side_effect = [requests.RequestException("first domain failed"), success]
    instance.query_domains = ["first.org", "example.com"]
    assert instance.search()[0]["href"] == "https://example.com/a"
    assert len(query_values(name, client)) == 2


def test_merge_interleaves_deduplicates_and_respects_total_limit():
    a, b, c = ({"href": f"https://example.com/{suffix}"} for suffix in "abc")
    d = {"href": "https://other.org/d"}
    assert UTILITY.merge_domain_results([[a, b, c], [d, a]], 3) == [a, d, b]
    assert UTILITY.merge_domain_results([[a], [d]], 0) == []


def test_unicode_domain_is_encoded_for_search():
    assert UTILITY.domain_queries("query", ["例子.测试"]) == [
        "(query) (site:xn--fsqu00a.xn--0zwm56d)"
    ]
