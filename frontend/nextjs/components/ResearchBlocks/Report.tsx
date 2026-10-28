import Image from "next/image";
import React, { useState, useEffect } from 'react';
import { toast } from "react-hot-toast";
import { markdownToHtml } from '../../helpers/markdownHelper';
import { getHost } from '../../helpers/getHost';
import '../../styles/markdown.css';
import { useResearchHistoryContext } from '../../hooks/ResearchHistoryContext';
import { ChatMessage } from '../../types/data';

interface EvidenceItem {
    number: number;
    source_type?: string;
    title?: string;
    page?: number | null;
    url?: string;
}

export default function Report({ answer, researchId }: { answer: string, researchId?: string }) {
    const [htmlContent, setHtmlContent] = useState('');
    const [evidenceItems, setEvidenceItems] = useState<EvidenceItem[]>([]);
    const [showEvidence, setShowEvidence] = useState(false);
    const [evidenceLoading, setEvidenceLoading] = useState(false);
    const { getChatMessages } = useResearchHistoryContext();
    // Memoize this value to prevent re-renders
    const chatMessages = researchId ? getChatMessages(researchId) : [];

    useEffect(() => {
      if (answer) {
        markdownToHtml(answer).then((html) => setHtmlContent(html));
      }
    }, [answer]);

    const openEvidence = async () => {
      if (!researchId) return;
      if (evidenceItems.length > 0) {
        setShowEvidence(true);
        return;
      }
      setEvidenceLoading(true);
      try {
        const response = await fetch(`${getHost()}/api/reports/${encodeURIComponent(researchId)}/evidence`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        setEvidenceItems(Array.isArray(payload.evidence) ? payload.evidence : []);
        setShowEvidence(true);
      } catch (error) {
        toast.error('证据清单加载失败');
      } finally {
        setEvidenceLoading(false);
      }
    };
    
    return (
      <div className="container flex h-auto w-full shrink-0 gap-4 bg-black/30 backdrop-blur-md shadow-lg rounded-lg border border-solid border-gray-700/40 p-5">
        <div className="w-full">
          <div className="flex items-center justify-between pb-3">
            <div className="flex items-center gap-3">
              <svg 
                xmlns="http://www.w3.org/2000/svg" 
                viewBox="0 0 24 24" 
                width={20}
                height={20}
                fill="none" 
                stroke="currentColor" 
                strokeWidth={1.5} 
                strokeLinecap="round" 
                strokeLinejoin="round" 
                className="text-teal-200"
              >
                <path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              <h3 className="text-sm font-medium text-teal-200">研究报告</h3>
            </div>
            {answer && (
              <div className="flex items-center gap-3">
                <button
                  onClick={() => {
                    navigator.clipboard.writeText(answer.trim());
                    toast("报告已复制到剪贴板", {
                      icon: "✂️",
                    });
                  }}
                  className="hover:opacity-80 transition-opacity duration-200"
                >
                  <img
                    src="/img/copy-white.svg"
                    alt="复制"
                    width={20}
                    height={20}
                    className="cursor-pointer text-white"
                  />
                </button>
                {researchId && (
                  <button
                    onClick={openEvidence}
                    className="text-xs text-teal-200 hover:text-teal-100 transition-colors"
                    aria-label="查看证据"
                  >
                    {evidenceLoading ? '加载中…' : '证据'}
                  </button>
                )}
              </div>
            )}
          </div>
          
          <div className="flex flex-wrap content-center items-center gap-[15px] pl-5 pr-5">
            <div className="w-full whitespace-pre-wrap text-base font-light leading-[152.5%] text-white log-message">
              {answer ? (
                <div className="markdown-content prose prose-invert max-w-none" dangerouslySetInnerHTML={{ __html: htmlContent }} />
              ) : (
                <div className="flex w-full flex-col gap-2">
                  <div className="h-6 w-full animate-pulse rounded-md bg-gray-300/20" />
                  <div className="h-6 w-[85%] animate-pulse rounded-md bg-gray-300/20" />
                  <div className="h-6 w-[90%] animate-pulse rounded-md bg-gray-300/20" />
                  <div className="h-6 w-[70%] animate-pulse rounded-md bg-gray-300/20" />
                </div>
              )}
            </div>
          </div>
        </div>
        {showEvidence && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4">
            <div className="max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-lg bg-gray-900 p-5 shadow-xl">
              <div className="mb-3 flex items-center justify-between">
                <h4 className="text-sm font-semibold text-teal-200">证据清单（{evidenceItems.length}）</h4>
                <button onClick={() => setShowEvidence(false)} className="text-gray-300 hover:text-white" aria-label="关闭">
                  ✕
                </button>
              </div>
              {evidenceItems.length === 0 ? (
                <p className="text-sm text-gray-400">本次报告没有可读取的证据快照。</p>
              ) : (
                <ul className="space-y-2">
                  {evidenceItems.map((item) => (
                    <li key={item.number} className="rounded border border-gray-700/60 bg-black/20 p-3 text-sm">
                      <a
                        href={researchId ? `${getHost()}/api/research-runs/${encodeURIComponent(researchId)}/evidence/${item.number}/view` : '#'}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="font-medium text-teal-300 hover:underline"
                      >
                        [E{item.number}] {item.title || `证据 ${item.number}`}
                      </a>
                      <div className="mt-1 text-xs text-gray-400">
                        {item.source_type || 'unknown'}
                        {item.page ? ` · 第 ${item.page} 页` : ''}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </div>
    );
}
