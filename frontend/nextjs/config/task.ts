export const task = {
  "task": {
    "query": "Is AI in a hype cycle?",
    "include_human_feedback": false,
    "model": "gpt-4o",
    "max_sections": 3,
    "publish_formats": {
      "markdown": true,
      "pdf": true,
      "docx": true
    },
    "source": "web",
    "follow_guidelines": true,
    "guidelines": [
      "报告必须完整回答原始问题",
      "报告必须使用 APA 格式",
      "报告必须使用简体中文撰写"
    ],
    "verbose": true
  },
  "initial_research": "Initial research data here",
  "sections": ["Section 1", "Section 2"],
  "research_data": "Research data here",
  "title": "Research Title",
  "headers": {
    "introduction": "Introduction header",
    "table_of_contents": "Table of Contents header",
    "conclusion": "Conclusion header",
    "sources": "Sources header"
  },
  "date": "2023-10-01",
  "table_of_contents": "- Introduction\n- Section 1\n- Section 2\n- Conclusion",
  "introduction": "Introduction content here",
  "conclusion": "Conclusion content here",
  "sources": ["Source 1", "Source 2"],
  "report": "Full report content here"
}
