'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import { apiJson } from '@/lib/api';

interface ChatMessage {
  id: string;
  sessionId: string;
  role: string;
  content: string;
  createdAt: string;
}

export default function ChatPage() {
  const [sessions, setSessions] = useState<string[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [typingIndex, setTypingIndex] = useState(-1);
  const [displayedText, setDisplayedText] = useState('');
  const [fullResponseText, setFullResponseText] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const typingTimerRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    apiJson<string[]>('/chat/sessions')
      .then(s => {
        setSessions(s);
        if (s.length > 0) loadSession(s[0]);
      })
      .catch(() => {});
  }, []);

  async function loadSession(sessionId: string) {
    setActiveSessionId(sessionId);
    setError('');
    try {
      const msgs = await apiJson<ChatMessage[]>(`/chat/sessions/${sessionId}`);
      setMessages(msgs);
      setTypingIndex(-1);
      setDisplayedText('');
      setFullResponseText('');
    } catch (e: any) {
      setError(e.message || 'Failed to load session');
    }
  }

  function startNewChat() {
    setActiveSessionId(null);
    setMessages([]);
    setTypingIndex(-1);
    setDisplayedText('');
    setFullResponseText('');
    setError('');
  }

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, displayedText]);

  useEffect(() => {
    if (!fullResponseText) return;
    let charIdx = 0;
    setDisplayedText('');
    if (typingTimerRef.current) clearInterval(typingTimerRef.current);
    typingTimerRef.current = setInterval(() => {
      charIdx++;
      if (charIdx >= fullResponseText.length) {
        setDisplayedText(fullResponseText);
        if (typingTimerRef.current) clearInterval(typingTimerRef.current);
        typingTimerRef.current = null;
        setTypingIndex(-1);
        setFullResponseText('');
      } else {
        setDisplayedText(fullResponseText.slice(0, charIdx));
      }
    }, 15);
    return () => {
      if (typingTimerRef.current) clearInterval(typingTimerRef.current);
    };
  }, [fullResponseText]);

  async function handleSend(e: React.FormEvent) {
    e.preventDefault();
    const text = input.trim();
    if (!text || sending) return;

    setInput('');
    setSending(true);
    setError('');

    const tempUserMsg: ChatMessage = {
      id: 'temp-user-' + Date.now(),
      sessionId: activeSessionId || '',
      role: 'user',
      content: text,
      createdAt: new Date().toISOString(),
    };
    setMessages(prev => [...prev, tempUserMsg]);

    try {
      const body: Record<string, string> = { message: text };
      if (activeSessionId) body.sessionId = activeSessionId;

      const resp = await apiJson<ChatMessage>('/chat', {
        method: 'POST',
        body: JSON.stringify(body),
      });

      if (!activeSessionId) {
        setActiveSessionId(resp.sessionId);
        setSessions(prev => prev.includes(resp.sessionId) ? prev : [resp.sessionId, ...prev]);
      }

      const assistantMsg: ChatMessage = {
        id: resp.id,
        sessionId: resp.sessionId,
        role: 'assistant',
        content: resp.content,
        createdAt: resp.createdAt,
      };
      setMessages(prev => [...prev, assistantMsg]);
      setTypingIndex(prev => messages.length + 1);
      setFullResponseText(resp.content);
    } catch (e: any) {
      setError(e.message || 'Failed to send message');
    } finally {
      setSending(false);
    }
  }

  function renderContent(msg: ChatMessage, idx: number) {
    if (idx === typingIndex && displayedText) {
      return displayedText;
    }
    return msg.content;
  }

  return (
    <div className="flex h-[calc(100vh-4rem)]">
      {/* Session sidebar */}
      <div className="w-64 border-r border-gray-200 bg-white flex flex-col">
        <div className="p-4 border-b border-gray-200">
          <button
            onClick={startNewChat}
            className="w-full px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700"
          >
            New Chat
          </button>
        </div>
        <div className="flex-1 overflow-y-auto">
          {sessions.length === 0 ? (
            <p className="p-4 text-sm text-gray-400">No conversations yet</p>
          ) : (
            sessions.map(sid => (
              <button
                key={sid}
                onClick={() => loadSession(sid)}
                className={`w-full text-left px-4 py-3 text-sm border-b border-gray-100 hover:bg-gray-50 ${
                  activeSessionId === sid ? 'bg-indigo-50 text-indigo-700' : 'text-gray-700'
                }`}
              >
                <span className="block truncate">Session {sid.slice(0, 8)}...</span>
              </button>
            ))
          )}
        </div>
      </div>

      {/* Chat area */}
      <div className="flex-1 flex flex-col">
        {/* Messages */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          {messages.length === 0 && !sending && (
            <div className="flex items-center justify-center h-full">
              <div className="text-center">
                <h2 className="text-xl font-semibold text-gray-900 mb-2">FinMind AI Chat</h2>
                <p className="text-gray-500 text-sm">Ask questions about your financial documents and spending.</p>
              </div>
            </div>
          )}
          {messages.map((msg, idx) => (
            <div key={msg.id} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[70%] px-4 py-3 rounded-lg ${
                msg.role === 'user'
                  ? 'bg-indigo-600 text-white'
                  : 'bg-white text-gray-800 shadow-sm border border-gray-100'
              }`}>
                <p className="text-sm whitespace-pre-wrap">{renderContent(msg, idx)}</p>
                <p className={`text-xs mt-1 ${msg.role === 'user' ? 'text-indigo-200' : 'text-gray-400'}`}>
                  {new Date(msg.createdAt).toLocaleTimeString()}
                </p>
              </div>
            </div>
          ))}
          {sending && (
            <div className="flex justify-start">
              <div className="bg-white text-gray-800 shadow-sm border border-gray-100 px-4 py-3 rounded-lg">
                <div className="flex space-x-1">
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                </div>
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        {error && (
          <div className="mx-6 mb-2 bg-red-50 border border-red-200 rounded-lg p-2 text-red-700 text-sm">{error}</div>
        )}

        {/* Input */}
        <form onSubmit={handleSend} className="p-4 border-t border-gray-200 bg-white">
          <div className="flex gap-3">
            <input
              type="text"
              value={input}
              onChange={e => setInput(e.target.value)}
              placeholder="Ask about your finances..."
              disabled={sending}
              className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 disabled:opacity-50"
            />
            <button
              type="submit"
              disabled={sending || !input.trim()}
              className="px-6 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-50"
            >
              Send
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
