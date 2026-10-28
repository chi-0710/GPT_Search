import React, { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import { useDropzone } from 'react-dropzone';
import { getHost } from '@/helpers/getHost';
import type { ChatBoxSettings } from '@/types/data';

interface StoredDocument {
  id: string;
  filename: string;
  status: 'processing' | 'ready' | 'failed';
  error?: string | null;
  size?: number;
  createdAt?: string;
}

interface FileUploadProps {
  settings?: ChatBoxSettings;
  onChange?: React.Dispatch<React.SetStateAction<ChatBoxSettings>>;
}

const FileUpload = ({ settings, onChange }: FileUploadProps) => {
  const [documents, setDocuments] = useState<StoredDocument[]>([]);
  const [error, setError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const host = getHost();
  const usesFilesForResearch = settings?.report_source === 'local' || settings?.report_source === 'hybrid';
  const selectedCount = (settings?.document_ids || []).length;

  const fetchDocuments = useCallback(async () => {
    try {
      const response = await axios.get(`${host}/api/documents`);
      setDocuments(response.data.documents || []);
    } catch (error) {
      console.error('Error fetching documents:', error);
      setError('读取资料库失败，请稍后刷新重试');
    }
  }, [host]);

  useEffect(() => {
    void fetchDocuments();
  }, [fetchDocuments]);

  useEffect(() => {
    if (!documents.some((document) => document.status === 'processing')) return;
    const timer = window.setTimeout(() => void fetchDocuments(), 2000);
    return () => window.clearTimeout(timer);
  }, [documents, fetchDocuments]);

  const onDrop = async (acceptedFiles: File[]) => {
    setError('');
    setUploading(true);
    for (const file of acceptedFiles) {
      const formData = new FormData();
      formData.append('file', file);
      try {
        await axios.post(`${host}/api/documents`, formData);
      } catch (error) {
        console.error('Error uploading document:', error);
        const message = axios.isAxiosError(error) ? error.response?.data?.detail || error.message : '上传失败';
        setError(previous => `${previous}\n${file.name}：${message}`.trim());
      }
    }
    await fetchDocuments();
    setUploading(false);
  };

  const deleteDocument = async (documentId: string) => {
    try {
      await axios.delete(`${host}/api/documents/${documentId}`);
      onChange?.(previous => ({...previous, document_ids: (previous.document_ids || []).filter(id => id !== documentId)}));
      await fetchDocuments();
    } catch (error) {
      console.error('Error deleting document:', error);
      setError('删除失败，请重试');
    }
  };

  const reindexDocument = async (documentId: string) => {
    try {
      await axios.post(`${host}/api/documents/${documentId}/reindex`);
      await fetchDocuments();
    } catch (error) {
      console.error('Error reindexing document:', error);
      setError('重新索引失败，请重试');
    }
  };

  const { getRootProps, getInputProps } = useDropzone({ onDrop, disabled: uploading });

  const statusText = {
    processing: '解析中',
    ready: '可检索',
    failed: '解析失败',
  };

  return (
    <section className="mb-4 w-full overflow-hidden rounded-lg border border-slate-600/70 bg-slate-900/30">
      <button
        type="button"
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left hover:bg-slate-800/60"
        onClick={() => setIsOpen(previous => !previous)}
        aria-expanded={isOpen}
        aria-controls="knowledge-library-content"
      >
        <span>
          <span className="block font-semibold text-white">我的资料库</span>
          <span className="block text-xs text-slate-300">
            {documents.length ? `${documents.length} 份文件` : '暂无文件'}
            {usesFilesForResearch && (settings?.document_scope || 'all') === 'selected'
              ? ` · 已选 ${selectedCount} 份`
              : usesFilesForResearch ? ' · 使用全部可检索资料' : ''}
          </span>
        </span>
        <svg className={`h-5 w-5 shrink-0 text-slate-300 transition-transform ${isOpen ? 'rotate-180' : ''}`} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path fillRule="evenodd" d="M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.17l3.71-3.94a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z" clipRule="evenodd" />
        </svg>
      </button>

      {isOpen && (
        <div id="knowledge-library-content" className="border-t border-slate-600/70 px-4 py-4">
          {usesFilesForResearch && settings && onChange ? (
            <label className="mb-3 block">
              文件检索范围
              <select aria-label="文件检索范围" value={settings.document_scope || 'all'}
                className="form-control-static"
                onChange={event => onChange(previous => ({...previous, document_scope: event.target.value as 'all' | 'selected'}))}>
                <option value="all">全部可检索资料</option>
                <option value="selected">指定文件</option>
              </select>
            </label>
          ) : (
            <p className="mb-3 text-sm text-amber-200">当前为“仅网络”，文件会保存到资料库，但不参与本次研究。</p>
          )}
          <p className="mb-2 text-xs text-slate-300">共享资料库：同名不同内容的文件会分别保存。文本型 PDF 支持页码引用；扫描件暂不支持。</p>
          {error && <p role="alert" className="whitespace-pre-wrap text-red-400">{error}</p>}
          <div
            {...getRootProps()}
            className="cursor-pointer rounded-md border-2 border-dashed border-slate-500 px-5 py-5 text-center transition-colors hover:border-teal-400 hover:bg-slate-800/50"
          >
            <input {...getInputProps()} />
            <p>{uploading ? '正在上传…' : '拖放文件，或点击批量选择文件'}</p>
          </div>
          {documents.length > 0 && (
            <>
              <h2 className="mt-4 text-lg font-medium text-white">已上传文件</h2>
              <ul role="list" className="my-2 divide-y divide-slate-700">
                {documents.map((document) => (
                  <li key={document.id} className="flex items-center justify-between gap-x-3 py-2">
                    {usesFilesForResearch && settings?.document_scope === 'selected' && <input type="checkbox"
                      aria-label={`选择 ${document.filename} ${document.createdAt || ''}`}
                      disabled={document.status !== 'ready'}
                      checked={(settings.document_ids || []).includes(document.id)}
                      onChange={event => {
                        const checked = event.target.checked;
                        onChange?.(previous => ({...previous, document_ids: checked
                          ? Array.from(new Set([...(previous.document_ids || []), document.id]))
                          : (previous.document_ids || []).filter(id => id !== document.id)}));
                      }} />}
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-white">{document.filename}</span>
                      <span className="block text-xs text-slate-300">{((document.size || 0) / 1024).toFixed(1)} KB · {document.createdAt ? new Date(document.createdAt).toLocaleString() : ''}</span>
                      <span className={document.status === 'failed' ? 'text-xs text-red-400' : 'text-xs text-slate-300'} title={document.error || undefined}>
                        {statusText[document.status]}{document.error ? `：${document.error}` : ''}
                      </span>
                    </span>
                    <a className="text-sm text-teal-300 hover:text-teal-200" href={`${host}/api/documents/${document.id}/content`}>下载</a>
                    {document.status !== 'processing' && <button className="text-sm text-teal-300 hover:text-teal-200" type="button" onClick={() => void reindexDocument(document.id)}>{document.status === 'failed' ? '重试' : '重新索引'}</button>}
                    <button className="text-sm text-red-300 hover:text-red-200" type="button" onClick={() => void deleteDocument(document.id)}>删除</button>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </section>
  );
};

export default FileUpload;
