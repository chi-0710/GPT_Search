import { normalizeResearchSettings } from '../utils/researchSettings';
import { useRef, useState, useEffect, useCallback } from 'react';
import { Data, ChatBoxSettings } from '../types/data';
import { getHost } from '../helpers/getHost';

export const useWebSocket = (
  setOrderedData: React.Dispatch<React.SetStateAction<Data[]>>,
  setAnswer: React.Dispatch<React.SetStateAction<string>>, 
  setLoading: React.Dispatch<React.SetStateAction<boolean>>,
  setShowHumanFeedback: React.Dispatch<React.SetStateAction<boolean>>,
  setQuestionForHuman: React.Dispatch<React.SetStateAction<boolean | true>>,
  onResearchStarted?: (researchId: string, runId?: string) => void,
) => {
  const [socket, setSocket] = useState<WebSocket | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const reviewRef = useRef<{run_id: string; round: number; request_id: string} | null>(null);
  const heartbeatInterval = useRef<number>();

  const clearHeartbeat = useCallback(() => {
    if (heartbeatInterval.current !== undefined) {
      window.clearInterval(heartbeatInterval.current);
      heartbeatInterval.current = undefined;
    }
  }, []);

  const releaseConnection = useCallback(() => {
    const current = socketRef.current;
    // Invalidate callbacks before closing, including a connection still opening.
    socketRef.current = null;
    reviewRef.current = null;
    clearHeartbeat();
    if (current && (current.readyState === WebSocket.CONNECTING ||
                    current.readyState === WebSocket.OPEN)) {
      current.close(1000, "Research connection closed");
    }
  }, [clearHeartbeat]);

  const closeConnection = useCallback(() => {
    releaseConnection();
    setSocket(null);
  }, [releaseConnection]);

  useEffect(() => releaseConnection, [releaseConnection]);

  const startHeartbeat = useCallback((ws: WebSocket) => {
    clearHeartbeat();
    heartbeatInterval.current = window.setInterval(() => {
      if (socketRef.current === ws && ws.readyState === WebSocket.OPEN) {
        ws.send('ping');
      }
    }, 30000);
  }, [clearHeartbeat]);

  const initializeWebSocket = useCallback((
    promptValue: string, 
    chatBoxSettings: ChatBoxSettings
  ) => {
    closeConnection();

    if (typeof window !== 'undefined') {
      
      let fullHost = getHost()
      const protocol = fullHost.includes('https') ? 'wss:' : 'ws:'
      const cleanHost = fullHost.replace('http://', '').replace('https://', '')
      const ws_uri = `${protocol}//${cleanHost}/ws`

      console.log(`Creating new WebSocket connection to ${ws_uri}`);
      const newSocket = new WebSocket(ws_uri);
      socketRef.current = newSocket;
      setSocket(newSocket);

      // WebSocket connection opened handler
      newSocket.onopen = () => {
        if (socketRef.current !== newSocket) return;
        console.log('WebSocket connection opened');
        
        const domains = chatBoxSettings.domains || [];
        const { report_type, report_source, tone, mcp_enabled, mcp_configs, mcp_strategy } = normalizeResearchSettings(chatBoxSettings);
        
        // Start a new research
        try {
          console.log(`Starting new research for: ${promptValue}`);
          const dataToSend = { 
            task: promptValue,
            report_type, 
            report_source,
            document_scope: chatBoxSettings.document_scope || "all",
            document_ids: chatBoxSettings.document_ids || [],
            tone,
            query_domains: domains,
            mcp_enabled: mcp_enabled || false,
            mcp_strategy: mcp_strategy || "fast",
            mcp_configs: mcp_configs || []
          };
          
          // Make sure we have a properly formatted command with a space after start
          const message = `start ${JSON.stringify(dataToSend)}`;
          console.log(`Sending start message, length: ${message.length}`);
          newSocket.send(message);
        } catch (error) {
          console.error("Error preparing start message:", error);
          closeConnection();
          setLoading(false);
          return;
        }
        
        startHeartbeat(newSocket);
      };

      newSocket.onmessage = (event) => {
        if (socketRef.current !== newSocket) return;
        try {
          // Handle ping response
          if (event.data === 'pong') return;

          // Try to parse JSON data
          console.log(`Received WebSocket message: ${event.data.substring(0, 100)}...`);
          const data = JSON.parse(event.data);
          
          if (data.type === 'error' || (data.type === 'logs' && data.content === 'error')) {
            setLoading(false);
            setOrderedData(prev => [...prev, {type: 'chat', content: data.output || '研究失败'}]);
          } else if (data.type === 'research_started') {
            onResearchStarted?.(data.research_id, data.run_id);
            const contentAndType = `${data.content}-${data.type}`;
            setOrderedData((prevOrder) => [...prevOrder, { ...data, contentAndType }]);
          } else if (data.type === 'human_feedback' && data.content === 'request') {
            reviewRef.current = data.run_id && data.request_id
              ? {run_id: data.run_id, round: data.round, request_id: data.request_id} : null;
            setQuestionForHuman(data.output);
            setShowHumanFeedback(true);
          } else {
            const contentAndType = `${data.content}-${data.type}`;
            setOrderedData((prevOrder) => [...prevOrder, { ...data, contentAndType }]);

            if (data.type === 'report') {
              setAnswer((prev: string) => prev + data.output);
            } else if (data.type === 'report_complete') {
              // Replace entire report with the complete version (includes images)
              console.log('Received complete report with images');
              setAnswer(data.output);
            } else if (data.type === 'path') {
              setLoading(false);
            }
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error, event.data);
        }
      };

      newSocket.onclose = () => {
        if (socketRef.current !== newSocket) return;
        closeConnection();
        setLoading(false);
      };

      newSocket.onerror = (error) => {
        if (socketRef.current !== newSocket) return;
        console.error('WebSocket error:', error);
        closeConnection();
        setLoading(false);
      };
    }
  }, [closeConnection, startHeartbeat, setOrderedData, setAnswer, setLoading, setShowHumanFeedback, setQuestionForHuman, onResearchStarted]);

  const sendHumanFeedback = useCallback((feedback: string | null) => {
    const current = socketRef.current;
    if (current?.readyState !== WebSocket.OPEN) return false;
    current.send(JSON.stringify({type: 'human_feedback', content: feedback ?? 'no', ...reviewRef.current}));
    reviewRef.current = null;
    return true;
  }, []);

  return { socket, setSocket, initializeWebSocket, closeConnection, sendHumanFeedback };
};
