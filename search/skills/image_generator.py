"""Image generator skill for ResearchRunner.

This module provides the ImageGenerator class that handles generating
contextually relevant images for research reports using AI image generation.
"""

import asyncio
import json
import logging
import re
from typing import Any, Dict, List, Optional, Tuple

from ..actions.utils import stream_output
from ..llm_provider.image import ImageGeneratorProvider, ModelsLabImageGeneratorProvider
from ..utils.llm import create_chat_completion

logger = logging.getLogger(__name__)


class ImageGenerator:
    """Generates contextually relevant images for research reports.
    
    This class analyzes report content to identify sections that would
    benefit from visual illustrations and generates images using AI
    image generation models.
    
    Attributes:
        researcher: The parent Research instance.
        image_provider: The image generation provider.
        max_images: Maximum number of images to generate per report.
    """
    
    def __init__(self, researcher):
        """Initialize the ImageGenerator.
        
        Args:
            researcher: The Research instance that owns this generator.
        """
        self.researcher = researcher
        self.cfg = researcher.cfg
        self.image_provider = None
        self.max_images = getattr(self.cfg, 'image_generation_max_images', 3)
        self.generated_images: List[Dict[str, Any]] = []
        
        # Initialize image provider if configured
        self._init_provider()
    
    def _init_provider(self):
        """Initialize the image generation provider from config."""
        try:
            enabled = getattr(self.cfg, 'IMAGE_GENERATION_ENABLED', False)
            if not enabled:
                return
            provider_name = getattr(self.cfg, 'IMAGE_GENERATION_PROVIDER', 'google')
            model = getattr(self.cfg, 'IMAGE_GENERATION_MODEL', None)
            artifact_sink = self.researcher.kwargs.get("artifact_sink")
            if provider_name == 'modelslab':
                provider = ModelsLabImageGeneratorProvider(
                    model_id=model, artifact_sink=artifact_sink
                )
            else:
                provider = ImageGeneratorProvider(
                    model_name=model, artifact_sink=artifact_sink
                )
            if provider.is_available():
                self.image_provider = provider
                logger.info(f"Image generation provider initialized: {provider_name}")
            else:
                logger.warning(f"Image generation provider '{provider_name}' not available (missing API key?)")
        except Exception as e:
            logger.error(f"Failed to initialize image provider: {e}")
            self.image_provider = None

    def is_enabled(self) -> bool:
        """Check if image generation is enabled and available.
        
        Returns:
            True if image generation can be used.
        """
        return self.image_provider is not None and self.image_provider.is_available()

    async def plan_and_generate_images(
        self,
        context: str,
        query: str,
        research_id: str = "",
    ) -> List[Dict[str, Any]]:
        """Plan and pre-generate images based on research context.
        
        This method analyzes the research context to identify 2-3 concepts
        that would benefit from visual illustrations, then generates them
        in parallel BEFORE report writing begins.
        
        Args:
            context: The accumulated research context.
            query: The main research query.
            research_id: Optional research ID for file organization.
            
        Returns:
            List of generated image info dictionaries with URLs ready to embed.
        """
        if not self.is_enabled():
            logger.info("Image generation is not enabled, skipping pre-generation")
            return []
        
        if self.researcher.verbose:
            await stream_output(
                "logs",
                "image_planning",
                '🎨 正在分析研究上下文，寻找适合可视化的内容...',
                self.researcher.websocket,
            )
        
        # Step 1: Use LLM to identify best visualization opportunities
        image_concepts = await self._plan_image_concepts(context, query)
        
        if not image_concepts:
            logger.info("No suitable visualization opportunities identified")
            return []
        
        if self.researcher.verbose:
            await stream_output(
                "logs",
                "image_concepts_identified",
                f"🖼️ 已识别 {len(image_concepts)} 个可视化概念，正在生成图片...",
                self.researcher.websocket,
            )
        
        # Step 2: Generate all images in parallel
        image_style = getattr(self.cfg, 'image_generation_style', 'dark')
        
        async def generate_single_image(concept: Dict[str, Any], index: int) -> Optional[Dict[str, Any]]:
            """Generate a single image from a concept."""
            try:
                if self.researcher.verbose:
                    await stream_output(
                        "logs",
                        "image_generating",
                        f"🖼️ 正在生成图片 {index + 1}/{len(image_concepts)}：{concept['title'][:50]}...",
                        self.researcher.websocket,
                    )
                
                images = await self.image_provider.generate_image(
                    prompt=concept['prompt'],
                    context=concept.get('context', ''),
                    research_id=research_id,
                    num_images=1,
                    style=image_style,
                )
                
                if images:
                    image_info = images[0]
                    image_info['title'] = concept['title']
                    image_info['section_hint'] = concept.get('section_hint', '')
                    return image_info
                    
            except Exception as e:
                logger.error(f"Failed to generate image for '{concept['title']}': {e}")
            return None
        
        # Generate all images in parallel
        tasks = [generate_single_image(concept, i) for i, concept in enumerate(image_concepts)]
        results = await asyncio.gather(*tasks)
        
        # Filter out failed generations
        generated_images = [img for img in results if img is not None]
        self.generated_images = generated_images
        
        if self.researcher.verbose:
            if generated_images:
                await stream_output(
                    "logs",
                    "images_ready",
                    f"✅ {len(generated_images)} 张图片已准备好，可嵌入报告",
                    self.researcher.websocket,
                )
            else:
                await stream_output(
                    "logs",
                    "images_failed",
                    '⚠️ 未能生成任何图片',
                    self.researcher.websocket,
                )
        
        return generated_images

    async def _plan_image_concepts(
        self,
        context: str,
        query: str,
    ) -> List[Dict[str, Any]]:
        """Use LLM to identify the best visualization opportunities from research context.
        
        Args:
            context: The research context to analyze.
            query: The main research query.
            
        Returns:
            List of image concept dictionaries with title, prompt, and section_hint.
        """
        # Truncate context if too long
        max_context_length = 6000
        truncated_context = context[:max_context_length] if len(context) > max_context_length else context
        
        planning_prompt = f"""分析以下研究上下文，找出 2–3 个能够显著受益于专业图解或信息图的概念。

研究查询：{query}

研究上下文：
{truncated_context}

为每个适合可视化的内容提供：
1. title：简短的描述性标题（例如“系统架构”“对比图”）
2. prompt：详细的图像生成提示词，准确描述需要可视化的内容，包括布局和关键元素（至少 30 词（words））
3. section_hint：该图像对应报告中的哪个章节

重点关注：
- 架构图或系统图
- 流程图和工作流
- 对比图
- 数据可视化
- 概念插图

重要：只能返回有效的 JSON 数组。不要使用 Markdown，不要附加解释。

输出示例：
[
  {{
    "title": "系统架构概览",
    "prompt": "一幅分层架构图：顶部为前端应用，中间连接 API 网关，网关再将请求路由到底部的微服务。使用简洁的方框和连接箭头，体现现代科技美感。",
    "section_hint": "架构"
  }}
]

以 JSON 数组返回 2–3 个可视化概念："""

        try:
            response = await create_chat_completion(
                model=self.cfg.fast_llm_model,
                messages=[
                    {"role": "system", "content": "你是一名可视化专家。只能返回有效的 JSON 数组。"},
                    {"role": "user", "content": planning_prompt}
                ],
                temperature=0.4,
                llm_provider=self.cfg.fast_llm_provider,
                max_tokens=4000,  # headroom for reasoning tokens on reasoning models
                llm_kwargs=self.cfg.llm_kwargs,
                cost_callback=self.researcher.add_costs,
            )
            
            # Parse JSON response
            response = response.strip()
            # Remove markdown code blocks if present
            if response.startswith("```"):
                response = re.sub(r'^```(?:json)?\n?', '', response)
                response = re.sub(r'\n?```$', '', response)
            
            concepts = json.loads(response)
            
            # Validate and limit to max_images
            valid_concepts = []
            for concept in concepts[:self.max_images]:
                if isinstance(concept, dict) and 'title' in concept and 'prompt' in concept:
                    valid_concepts.append(concept)
            
            logger.info(f"Planned {len(valid_concepts)} image concepts")
            return valid_concepts
            
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse image planning response: {e}")
            return []
        except Exception as e:
            logger.error(f"Error during image planning: {e}")
            return []

    async def analyze_report_for_images(
        self,
        report: str,
        query: str,
    ) -> List[Dict[str, Any]]:
        """Analyze a report to identify sections that would benefit from images.
        
        Uses LLM to identify 2-3 key concepts or sections in the report
        that would be enhanced by visual illustrations.
        
        Args:
            report: The markdown report text.
            query: The original research query.
            
        Returns:
            List of dictionaries with section info and suggested image prompts.
        """
        if not self.is_enabled():
            return []
        
        # Extract sections from the report
        sections = self._extract_sections(report)
        
        if not sections:
            logger.warning("No sections found in report for image analysis")
            return []
        
        # Use LLM to identify best sections for images
        try:
            analysis_prompt = self._build_analysis_prompt(query, sections)
            
            response = await create_chat_completion(
                model=self.cfg.fast_llm_model,
                messages=[
                    {"role": "system", "content": "你是一名擅长识别哪些内容能受益于视觉插图的专家。"},
                    {"role": "user", "content": analysis_prompt},
                ],
                temperature=0.3,
                llm_provider=self.cfg.fast_llm_provider,
                stream=False,
                websocket=None,
                max_tokens=4000,  # headroom for reasoning tokens on reasoning models
                llm_kwargs=self.cfg.llm_kwargs,
            )
            
            # Parse the response
            image_suggestions = self._parse_analysis_response(response, sections)
            return image_suggestions[:self.max_images]
            
        except Exception as e:
            logger.error(f"Error analyzing report for images: {e}")
            return []
    
    def _extract_sections(self, report: str) -> List[Dict[str, Any]]:
        """Extract sections from a markdown report.
        
        Args:
            report: The markdown report text.
            
        Returns:
            List of section dictionaries with header, content, and position.
        """
        sections = []
        lines = report.split('\n')
        current_section = None
        current_content = []
        section_start = 0
        
        for i, line in enumerate(lines):
            # Check for headers (## or ###)
            header_match = re.match(r'^(#{2,3})\s+(.+)$', line)
            
            if header_match:
                # Save previous section
                if current_section:
                    sections.append({
                        "header": current_section,
                        "content": '\n'.join(current_content).strip(),
                        "start_line": section_start,
                        "end_line": i - 1,
                    })
                
                # Start new section
                current_section = header_match.group(2)
                current_content = []
                section_start = i
            elif current_section:
                current_content.append(line)
        
        # Don't forget the last section
        if current_section:
            sections.append({
                "header": current_section,
                "content": '\n'.join(current_content).strip(),
                "start_line": section_start,
                "end_line": len(lines) - 1,
            })
        
        return sections
    
    def _build_analysis_prompt(
        self,
        query: str,
        sections: List[Dict[str, Any]],
    ) -> str:
        """Build prompt for LLM to analyze which sections need images.
        
        Args:
            query: The research query.
            sections: List of extracted sections.
            
        Returns:
            The analysis prompt string.
        """
        sections_text = "\n\n".join([
            f"### 第 {i+1} 节：{s['header']}\n{s['content'][:500]}..."
            for i, s in enumerate(sections)
        ])
        
        return f"""分析以下研究报告章节，确定其中哪 {self.max_images} 个章节最能受益于插图或图解。

研究主题：{query}

报告章节：
{sections_text}

为每个推荐的章节提供：
1. 章节编号（从 1 开始）
2. 一条具体、详细的图像提示词，用于生成具有信息价值的插图
3. 简要解释该章节为何适合可视化

重要：
- 选择视觉表达确实能帮助理解的章节
- 聚焦本身适合视觉表达的概念、过程、比较或数据
- 避开纯文字分析或结论章节
- 图像提示词应足够具体，以生成相关、专业的插图
- 不得建议为引言或结论章节配图

以 JSON 格式回复：
{{
    "suggestions": [
        {{
            "section_number": 1,
            "section_header": "章节标题",
            "image_prompt": "用于生成信息性插图的详细提示词……",
            "reason": "该章节适合可视化的原因"
        }}
    ]
}}

只返回 JSON，不要附加任何文字。"""
    
    def _parse_analysis_response(
        self,
        response: str,
        sections: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """Parse the LLM's analysis response.
        
        Args:
            response: The LLM response text.
            sections: The original sections list.
            
        Returns:
            List of image suggestion dictionaries.
        """
        try:
            # Try to extract JSON from the response
            json_match = re.search(r'\{[\s\S]*\}', response)
            if not json_match:
                logger.warning("No JSON found in analysis response")
                return []
            
            data = json.loads(json_match.group())
            suggestions = data.get("suggestions", [])
            
            # Enrich with section data
            enriched = []
            for s in suggestions:
                section_num = s.get("section_number", 0) - 1  # Convert to 0-indexed
                if 0 <= section_num < len(sections):
                    section = sections[section_num]
                    enriched.append({
                        "section_header": section["header"],
                        "section_content": section["content"][:1000],
                        "image_prompt": s.get("image_prompt", ""),
                        "reason": s.get("reason", ""),
                        "insert_after_line": section["start_line"],
                    })
            
            return enriched
            
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse analysis JSON: {e}")
            return []
    
    async def generate_images_for_report(
        self,
        report: str,
        query: str,
        research_id: str = "",
    ) -> Tuple[str, List[Dict[str, Any]]]:
        """Generate images and embed them in the report.
        
        This is the main method that orchestrates the full image generation
        workflow for a research report.
        
        Args:
            report: The markdown report text.
            query: The original research query.
            research_id: Optional research ID for file organization.
            
        Returns:
            Tuple of (modified report with embedded images, list of generated images).
        """
        if not self.is_enabled():
            logger.info("Image generation is not enabled, skipping")
            return report, []
        
        # Notify about image generation starting
        if self.researcher.verbose:
            await stream_output(
                "logs",
                "image_generation_start",
                '🎨 正在分析报告，寻找适合配图的内容...',
                self.researcher.websocket,
            )
        
        # Analyze report for image opportunities
        suggestions = await self.analyze_report_for_images(report, query)
        
        if not suggestions:
            logger.info("No sections identified for image generation")
            if self.researcher.verbose:
                await stream_output(
                    "logs",
                    "image_generation_skip",
                    '📝 未发现适合配图的章节',
                    self.researcher.websocket,
                )
            return report, []
        
        if self.researcher.verbose:
            await stream_output(
                "logs",
                "image_generation_analyzing",
                f"🔍 已找到 {len(suggestions)} 个适合配图的章节",
                self.researcher.websocket,
            )
        
        # Generate images for each suggestion
        generated_images = []
        for i, suggestion in enumerate(suggestions):
            if self.researcher.verbose:
                await stream_output(
                    "logs",
                    "image_generating",
                    f"🖼️ 正在生成图片 {i+1}/{len(suggestions)}：{suggestion['section_header'][:50]}...",
                    self.researcher.websocket,
                )
            
            try:
                images = await self.image_provider.generate_image(
                    prompt=suggestion["image_prompt"],
                    context=suggestion["section_content"],
                    research_id=research_id,
                    num_images=1,
                )
                
                if images:
                    image_info = images[0]
                    image_info["section_header"] = suggestion["section_header"]
                    generated_images.append(image_info)
                    
                    if self.researcher.verbose:
                        await stream_output(
                            "logs",
                            "image_generated",
                            f"✅ 已为以下章节生成图片：{suggestion['section_header'][:50]}",
                            self.researcher.websocket,
                        )
            except Exception as e:
                logger.error(f"Failed to generate image for section '{suggestion['section_header']}': {e}")
                if self.researcher.verbose:
                    await stream_output(
                        "logs",
                        "image_generation_error",
                        f"⚠️ 图片生成失败：{str(e)[:100]}",
                        self.researcher.websocket,
                    )
        
        # Embed images in the report
        if generated_images:
            report = self._embed_images_in_report(report, generated_images, suggestions)
            self.generated_images = generated_images
            
            if self.researcher.verbose:
                await stream_output(
                    "logs",
                    "image_generation_complete",
                    f"🎉 已成功生成并嵌入 {len(generated_images)} 张图片",
                    self.researcher.websocket,
                )
                
                # Send generated images through WebSocket
                await stream_output(
                    "generated_images",
                    "inline_images",
                    json.dumps([{"url": img["url"], "alt": img["alt_text"]} for img in generated_images]),
                    self.researcher.websocket,
                    True,
                    generated_images,
                )
        
        return report, generated_images
    
    def _embed_images_in_report(
        self,
        report: str,
        images: List[Dict[str, Any]],
        suggestions: List[Dict[str, Any]],
    ) -> str:
        """Embed generated images into the report markdown.
        
        Args:
            report: The original report markdown.
            images: List of generated image info.
            suggestions: Original suggestions with section info.
            
        Returns:
            Modified report with embedded images.
        """
        lines = report.split('\n')
        
        # Create a mapping of section headers to images
        section_to_image = {}
        for img, sug in zip(images, suggestions):
            section_to_image[sug["section_header"]] = img
        
        # Find section headers and insert images after them
        modified_lines = []
        i = 0
        while i < len(lines):
            line = lines[i]
            modified_lines.append(line)
            
            # Check if this is a header that needs an image
            header_match = re.match(r'^(#{2,3})\s+(.+)$', line)
            if header_match:
                header_text = header_match.group(2)
                if header_text in section_to_image:
                    img = section_to_image[header_text]
                    # Insert image after the header with a blank line
                    image_markdown = f"\n![{img['alt_text']}]({img['url']})\n"
                    modified_lines.append(image_markdown)
            
            i += 1
        
        return '\n'.join(modified_lines)
    
    def get_generated_images(self) -> List[Dict[str, Any]]:
        """Get the list of generated images.
        
        Returns:
            List of generated image info dictionaries.
        """
        return self.generated_images

    async def process_image_placeholders(
        self,
        report: str,
        query: str,
        research_id: str = "",
    ) -> Tuple[str, List[Dict[str, Any]]]:
        """Process [IMAGE: description] placeholders in the report and generate images.
        
        This method finds all image placeholders in the report, generates images
        for each one, and replaces the placeholders with actual markdown images.
        
        Args:
            report: The markdown report text with [IMAGE: ...] placeholders.
            query: The original research query (used for context).
            research_id: Optional research ID for file organization.
            
        Returns:
            Tuple of (modified report with images embedded, list of generated images).
        """
        if not self.is_enabled():
            # If image generation is not enabled, just remove the placeholders
            report = re.sub(r'\[IMAGE:\s*[^\]]+\]', '', report)
            return report, []
        
        # Find all image placeholders
        placeholder_pattern = r'\[IMAGE:\s*([^\]]+)\]'
        placeholders = list(re.finditer(placeholder_pattern, report))
        
        if not placeholders:
            logger.info("No image placeholders found in report")
            return report, []
        
        # Limit to max_images
        placeholders = placeholders[:self.max_images]
        
        if self.researcher.verbose:
            await stream_output(
                "logs",
                "image_placeholders_found",
                f"🎨 发现 {len(placeholders)} 个待处理的图片占位符",
                self.researcher.websocket,
            )
        
        generated_images = []
        replacements = []  # List of (original_text, replacement_text) tuples
        
        for i, match in enumerate(placeholders):
            image_description = match.group(1).strip()
            original_text = match.group(0)
            
            if self.researcher.verbose:
                await stream_output(
                    "logs",
                    "image_generating",
                    f"🖼️ 正在生成图片 {i+1}/{len(placeholders)}：{image_description[:60]}...",
                    self.researcher.websocket,
                )
            
            try:
                # Get image style from config (default to "dark" for app theme)
                image_style = getattr(self.cfg, 'image_generation_style', 'dark')
                logger.info(f"Using image style: {image_style}")
                
                # Generate the image with dark mode styling
                images = await self.image_provider.generate_image(
                    prompt=image_description,
                    context=query,  # Use query as context
                    research_id=research_id,
                    num_images=1,
                    style=image_style,
                )
                
                if images:
                    image_info = images[0]
                    image_info["description"] = image_description
                    generated_images.append(image_info)
                    
                    # Create markdown replacement with absolute path for PDF compatibility
                    # Use the absolute URL for proper rendering in PDF/DOCX
                    markdown_image = f"\n\n![{image_info['alt_text']}]({image_info['url']})\n\n"
                    replacements.append((original_text, markdown_image))
                    
                    if self.researcher.verbose:
                        await stream_output(
                            "logs",
                            "image_generated",
                            f"✅ 已生成：{image_description[:40]}...",
                            self.researcher.websocket,
                        )
                else:
                    # Remove placeholder if image generation failed
                    replacements.append((original_text, ""))
                    logger.warning(f"No image generated for: {image_description[:50]}")
                    
            except Exception as e:
                logger.error(f"Failed to generate image for '{image_description[:50]}': {e}")
                # Remove the failed placeholder
                replacements.append((original_text, ""))
                
                if self.researcher.verbose:
                    await stream_output(
                        "logs",
                        "image_generation_error",
                        f"⚠️ 生成失败：{str(e)[:80]}",
                        self.researcher.websocket,
                    )
        
        # Apply all replacements
        modified_report = report
        for original, replacement in replacements:
            modified_report = modified_report.replace(original, replacement, 1)
        
        self.generated_images = generated_images
        
        if generated_images and self.researcher.verbose:
            await stream_output(
                "logs",
                "image_generation_complete",
                f"🎉 已成功生成 {len(generated_images)} 张文内图片",
                self.researcher.websocket,
            )
            
            # Send generated images through WebSocket
            await stream_output(
                "generated_images",
                "inline_images",
                json.dumps([{"url": img["url"], "alt": img["alt_text"]} for img in generated_images]),
                self.researcher.websocket,
                True,
                generated_images,
            )
        
        return modified_report, generated_images
