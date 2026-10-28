from .utils.views import print_agent_output


class PublisherAgent:
    def __init__(self, output_dir: str, websocket=None, stream_output=None, headers=None):
        self.websocket = websocket
        self.stream_output = stream_output
        self.output_dir = output_dir.strip()
        self.headers = headers or {}
        
    def generate_layout(self, research_state: dict):
        sections = []
        for subheader in research_state.get("research_data", []):
            if isinstance(subheader, dict):
                # Handle dictionary case
                for key, value in subheader.items():
                    sections.append(f"{value}")
            else:
                # Handle string case
                sections.append(f"{subheader}")
        
        sections_text = '\n\n'.join(sections)
        references = '\n'.join(f"{reference}" for reference in research_state.get("sources", []))
        headers = research_state.get("headers", {})
        
        diagrams_text = ""
        diagrams = research_state.get("diagrams", [])
        if diagrams:
            diagrams_text = "\n## 可视化\n" + "\n\n".join(diagrams) + "\n"

        layout = f"""# {headers.get('title')}
#### {headers.get("date")}: {research_state.get('date')}

## {headers.get("introduction")}
{research_state.get('introduction')}

## {headers.get("table_of_contents")}
{research_state.get('table_of_contents')}

{sections_text}

## {headers.get("conclusion")}
{research_state.get('conclusion')}
{diagrams_text}
## {headers.get("references")}
{references}
"""
        return layout

    async def run(self, research_state: dict):
        """Assemble Markdown; the application/CLI owns persisted exports."""
        if self.websocket and self.stream_output:
            await self.stream_output("logs", "publishing", f"正在根据检索到的资料发布最终研究报告...", self.websocket)
        else:
            print_agent_output(output="Publishing final research report based on retrieved data...", agent="PUBLISHER")
        final_research_report = self.generate_layout(research_state)
        return {"report": final_research_report}
