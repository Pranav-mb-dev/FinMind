'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { apiFetch, apiJson } from '@/lib/api';

interface Alert {
  id: string;
  type: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  message: string;
  read: boolean;
  createdAt: string;
}

interface AlertList {
  unreadCount: number;
  alerts: Alert[];
}

const POLL_MS = 60_000;

const severityDot: Record<Alert['severity'], string> = {
  INFO: 'bg-blue-400',
  WARNING: 'bg-amber-400',
  CRITICAL: 'bg-red-500',
};

function timeAgo(iso: string): string {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export default function AlertBell() {
  const [data, setData] = useState<AlertList>({ unreadCount: 0, alerts: [] });
  const [open, setOpen] = useState(false);
  const [failed, setFailed] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      setData(await apiJson<AlertList>('/alerts'));
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  useEffect(() => {
    if (!open) return;
    function onClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  async function markRead(a: Alert) {
    if (a.read) return;
    setData(d => ({
      unreadCount: Math.max(0, d.unreadCount - 1),
      alerts: d.alerts.map(x => (x.id === a.id ? { ...x, read: true } : x)),
    }));
    try {
      await apiFetch(`/alerts/${a.id}/read`, { method: 'POST' });
    } catch {
      load();
    }
  }

  async function markAllRead() {
    setData(d => ({ unreadCount: 0, alerts: d.alerts.map(x => ({ ...x, read: true })) }));
    try {
      await apiFetch('/alerts/read-all', { method: 'POST' });
    } catch {
      load();
    }
  }

  return (
    <div ref={rootRef} className="relative">
      <button
        onClick={() => setOpen(o => !o)}
        className="relative text-gray-400 hover:text-white transition-colors"
        title="Alerts"
        aria-label={`Alerts${data.unreadCount ? `, ${data.unreadCount} unread` : ''}`}
      >
        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"
          />
        </svg>
        {data.unreadCount > 0 && (
          <span className="absolute -top-1.5 -right-1.5 min-w-[16px] h-4 px-1 rounded-full bg-red-500 text-white text-[10px] font-bold leading-4 text-center">
            {data.unreadCount > 9 ? '9+' : data.unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute left-0 top-full mt-3 w-80 max-h-96 overflow-y-auto bg-white text-gray-900 rounded-lg shadow-lg border border-gray-200 z-50">
          <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
            <h3 className="text-sm font-semibold">Alerts</h3>
            {data.unreadCount > 0 && (
              <button onClick={markAllRead} className="text-xs text-indigo-600 hover:underline">
                Mark all read
              </button>
            )}
          </div>
          {failed && data.alerts.length === 0 ? (
            <p className="px-4 py-6 text-sm text-center text-gray-500">Couldn&apos;t load alerts.</p>
          ) : data.alerts.length === 0 ? (
            <p className="px-4 py-6 text-sm text-center text-gray-500">
              No alerts yet. We&apos;ll tell you when a budget is nearly used up or a new subscription shows up.
            </p>
          ) : (
            <ul>
              {data.alerts.map(a => (
                <li key={a.id}>
                  <button
                    onClick={() => markRead(a)}
                    className={`w-full text-left px-4 py-3 border-b border-gray-50 hover:bg-gray-50 flex gap-3 ${
                      a.read ? 'opacity-60' : ''
                    }`}
                  >
                    <span className={`mt-1.5 w-2 h-2 rounded-full shrink-0 ${severityDot[a.severity] ?? 'bg-gray-400'}`} />
                    <span className="min-w-0">
                      <span className="block text-sm text-gray-800">{a.message}</span>
                      <span className="block text-xs text-gray-400 mt-0.5">{timeAgo(a.createdAt)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
