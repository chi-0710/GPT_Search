import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-hot-toast';
import { ChatMessage, Data, ResearchHistoryItem } from '../types/data';

async function readJson(response: Response) {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || `API error: ${response.status}`);
  }
  return response.json();
}

export const useResearchHistory = () => {
  const [history, setHistory] = useState<ResearchHistoryItem[]>([]);
  const [loading, setLoading] = useState(true);

  const refreshHistory = useCallback(async () => {
    setLoading(true);
    try {
      const data = await readJson(
        await fetch('/api/reports?limit=100&include_incomplete=true'),
      );
      setHistory(Array.isArray(data.reports) ? data.reports : []);
    } catch (error) {
      console.error('Error fetching research history:', error);
      setHistory([]);
      toast.error('无法从服务器读取研究历史。');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshHistory();
  }, [refreshHistory]);

  const saveResearch = async (
    question: string,
    answer: string,
    orderedData: Data[],
  ) => {
    try {
      const data = await readJson(
        await fetch('/api/reports', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ question, answer, orderedData, chatMessages: [] }),
        }),
      );
      const item: ResearchHistoryItem = {
        id: data.id,
        question,
        answer,
        orderedData,
        chatMessages: [],
        timestamp: Date.now(),
      };
      setHistory((previous) => [item, ...previous.filter((entry) => entry.id !== item.id)]);
      return data.id as string;
    } catch (error) {
      console.error('Error saving research:', error);
      toast.error('研究未能保存到服务器。');
      throw error;
    }
  };

  const updateResearch = async (id: string, answer: string, orderedData: Data[]) => {
    try {
      await readJson(
        await fetch(`/api/reports/${id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ answer, orderedData }),
        }),
      );
      setHistory((previous) =>
        previous.map((item) =>
          item.id === id ? { ...item, answer, orderedData, timestamp: Date.now() } : item,
        ),
      );
      return true;
    } catch (error) {
      console.error('Error updating research:', error);
      toast.error('研究更新未保存到服务器。');
      return false;
    }
  };

  const getResearchById = async (id: string): Promise<ResearchHistoryItem | null> => {
    try {
      const response = await fetch(`/api/reports/${id}`);
      if (response.status === 404) return null;
      const data = await readJson(response);
      return data.report || null;
    } catch (error) {
      console.error('Error getting research by ID:', error);
      return null;
    }
  };

  const deleteResearch = async (id: string) => {
    try {
      await readJson(await fetch(`/api/reports/${id}`, { method: 'DELETE' }));
      setHistory((previous) => previous.filter((item) => item.id !== id));
      return true;
    } catch (error) {
      console.error('Error deleting research:', error);
      toast.error('无法从服务器删除该研究。');
      return false;
    }
  };

  const addChatMessage = async (id: string, message: ChatMessage) => {
    const storedMessage: ChatMessage = {
      ...message,
      id: message.id || crypto.randomUUID(),
    };
    try {
      await readJson(
        await fetch(`/api/reports/${id}/chat`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(storedMessage),
        }),
      );
      setHistory((previous) =>
        previous.map((item) =>
          item.id === id
            ? { ...item, chatMessages: [...(item.chatMessages || []), storedMessage] }
            : item,
        ),
      );
      return true;
    } catch (error) {
      console.error('Error adding chat message:', error);
      toast.error('聊天消息未保存到服务器。');
      return false;
    }
  };

  const getChatMessages = (id: string) =>
    history.find((item) => item.id === id)?.chatMessages || [];

  const clearHistory = async () => {
    try {
      await readJson(await fetch('/api/reports', { method: 'DELETE' }));
      setHistory([]);
      return true;
    } catch (error) {
      console.error('Error clearing history:', error);
      toast.error('无法清空服务器研究历史。');
      return false;
    }
  };

  return {
    history,
    loading,
    saveResearch,
    updateResearch,
    getResearchById,
    deleteResearch,
    addChatMessage,
    getChatMessages,
    clearHistory,
    refreshHistory,
  };
};
