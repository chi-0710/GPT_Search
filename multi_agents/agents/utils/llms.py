import json_repair
from langchain_community.adapters.openai import convert_openai_messages
from langchain_core.utils.json import parse_json_markdown
from loguru import logger

from search.config.config import Config
from search.utils.llm import create_chat_completion
from multi_agents.runtime import active_runtime


async def call_model(
    prompt: list,
    model: str,
    response_format: str | None = None,
):

    runtime = active_runtime.get()
    cfg = Config(runtime.options.get("config_path", "")) if runtime else Config()
    lc_messages = convert_openai_messages(prompt)
    if runtime and runtime.evidence_registry:
        from search.utils.evidence import CITATION_INSTRUCTIONS
        evidence = runtime.evidence_registry.snapshots()
        blocks = []
        # Reserve space for every source, including later parallel chapters.
        per_source_budget = max(1, 24000 // max(1, len(evidence)))
        for item in evidence:
            content = item["content"][:per_source_budget]
            blocks.append(f"[E{item['number']}] {item['title']}\n{content}")
        lc_messages = convert_openai_messages([
            {"role": "system", "content": CITATION_INSTRUCTIONS
             + "修改和汇总时保留原有证据编号。事实核查必须与以下原始证据比对。\n"
             + "\n\n".join(blocks)}, *prompt
        ])

    try:
        response = await create_chat_completion(
            model=model or cfg.strategic_llm_model or cfg.smart_llm_model,
            messages=lc_messages,
            temperature=0,
            llm_provider=cfg.strategic_llm_provider or cfg.smart_llm_provider,
            llm_kwargs=cfg.llm_kwargs,
            cost_callback=runtime.add_model_cost if runtime else None,
        )

        if response_format == "json":
            parsed = parse_json_markdown(response, parser=json_repair.loads)
            if not isinstance(parsed, dict):
                raise ValueError("智能体未返回有效的 JSON 对象")
            return parsed

        return response

    except Exception as e:
        print("⚠️ Error in calling model")
        logger.error(f"Error in calling model: {e}")
        raise
