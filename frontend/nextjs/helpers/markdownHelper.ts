import { remark } from 'remark';
import html from 'remark-html';
import remarkGfm from 'remark-gfm';
import { Compatible, VFile } from "vfile";
import DOMPurify from 'isomorphic-dompurify';
import { getHost } from './getHost';

/**
 * Adds target="_blank" and rel="noopener noreferrer" attributes to all links in HTML content
 * @param htmlContent - The HTML content containing links
 * @returns The processed HTML with target="_blank" added to all links
 */
export const addTargetBlankToLinks = (htmlContent: string): string => {
  return htmlContent.replace(
    /<a(.*?)href="(.*?)"(.*?)>/gi,
    '<a$1href="$2"$3 target="_blank" rel="noopener noreferrer">'
  );
};

/**
 * Fixes the list item paragraph issue in HTML content
 * This specifically addresses the problem where numbered list items with bold text
 * have an extra line break between the marker and content
 * @param htmlContent - The HTML content with possible list formatting issues
 * @returns The processed HTML with fixed list formatting
 */
export const fixListItemParagraphIssue = (htmlContent: string): string => {
  // This regex looks for list items with a paragraph immediately inside
  // and removes the paragraph tags while preserving the content
  return htmlContent.replace(
    /<li>\s*<p>([\s\S]*?)<\/p>/g,
    '<li>$1'
  );
};

export const resolveArtifactUrls = (htmlContent: string): string => {
  const host = getHost().replace(/\/$/, '');
  if (!host) return htmlContent;
  return htmlContent.replace(
    /(src|href)="(\/api\/(?:artifacts\/[0-9a-f-]{36}|research-runs\/[0-9a-f-]{36}\/evidence\/\d+\/view))"/gi,
    (_match, attribute, path) => `${attribute}="${host}${path}"`,
  );
};

/** Escape table delimiters in citation labels saved by older report versions. */
export const escapeCitationTitlePipes = (markdown: string): string => {
  // Older reports contain unescaped table delimiters inside citation labels.
  return markdown.replace(
    /(?<!!)\[((?:网页|资料) \d+：(?:\\[^\r\n]|[^\]\\\r\n])*)\](\((?:https?:\/\/|\/api\/)[^\s]*?\))/g,
    (_match, label: string, target: string) =>
      `[${label.replace(/\\.|\|/g, token => token === '|' ? '\\|' : token)}]${target}`,
  );
};

/**
 * Converts markdown to HTML with GitHub Flavored Markdown support and adds target="_blank" to links
 * @param markdown - The markdown content to convert
 * @returns Promise with the HTML content
 */
export const markdownToHtml = async (markdown: Compatible | string): Promise<string> => {
  try {
    const result = await remark()
      .use(remarkGfm) // Add GitHub Flavored Markdown support (tables, strikethrough, etc.)
      .use(html, { sanitize: false })
      .process(escapeCitationTitlePipes(new VFile(markdown).toString()));
    
    // Get the HTML string
    let htmlString = result.toString();
    
    // Apply fixes
    htmlString = fixListItemParagraphIssue(htmlString);
    htmlString = addTargetBlankToLinks(htmlString);
    htmlString = resolveArtifactUrls(htmlString);

    // Sanitize the final HTML to prevent XSS. Report content is derived from
    // untrusted sources (scraped web pages and LLM output) and is rendered via
    // dangerouslySetInnerHTML, so it must be sanitized before being returned.
    htmlString = DOMPurify.sanitize(htmlString, { ADD_ATTR: ['target'] });

    return htmlString;
  } catch (error) {
    console.error('Error converting Markdown to HTML:', error);
    return ''; // Handle error gracefully, return empty string or default content
  }
};
