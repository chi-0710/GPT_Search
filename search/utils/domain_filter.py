"""Shared domain normalization and request-side search restrictions."""

from itertools import zip_longest
from urllib.parse import urlsplit


def domain_hostname(value: str, *, allow_bare: bool = False) -> str | None:
    """Normalize an HTTP URL or allowed domain to its IDNA hostname."""
    if not isinstance(value, str) or not value.strip():
        return None
    value = value.strip()
    if allow_bare and "://" not in value:
        value = "https://" + value
    try:
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return None
        if parsed.username is not None or parsed.password is not None:
            return None
        # Accessing port also rejects malformed port values.
        parsed.port
        hostname = parsed.hostname.rstrip(".").encode("idna").decode("ascii").lower()
        if not hostname or any(
            not label or label.startswith("-") or label.endswith("-")
            or any(not (char.isalnum() or char == "-") for char in label)
            for label in hostname.split(".")
        ):
            return None
        return hostname
    except (ValueError, UnicodeError):
        return None


def domain_queries(query: str, domains: list | None, *, combine: bool = True) -> list[str]:
    """Build site queries; invalid nonempty restrictions never become unrestricted."""
    if not domains:
        return [query]
    hosts = list(dict.fromkeys(
        host for domain in domains
        if (host := domain_hostname(domain, allow_bare=True))
    ))
    if not hosts:
        return []
    if not combine:
        return [f"{query} site:{host}" for host in hosts]
    sites = " OR ".join(f"site:{host}" for host in hosts)
    return [f"({query}) ({sites})"]


def merge_domain_results(batches: list[list[dict]], max_results: int) -> list[dict]:
    """Interleave domain results, removing duplicate URLs within the total limit."""
    merged = []
    seen = set()
    for row in zip_longest(*batches):
        for result in row:
            if len(merged) >= max_results:
                return merged
            if result is None:
                continue
            url = result.get("href") or result.get("url")
            if url and url not in seen:
                seen.add(url)
                merged.append(result)
    return merged

