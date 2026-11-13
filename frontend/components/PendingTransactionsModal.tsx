'use client';

import { Fragment, useEffect, useRef, useState } from 'react';
import { apiJson } from '@/lib/api';

interface PendingTransaction {
  id: string;
  documentId: string;
  categoryId: string | null;
  amount: number;
  type: string;
  description: string | null;
  transactionDate: string;
  suggestedCategory: string | null;
}

type EditableField = 'categoryId' | 'amount' | 'type' | 'description' | 'transactionDate';

interface Category {
  id: string;
  name: string;
}

interface Props {
  documentId: string;
  onClose: () => void;
  onImported: () => void;
}

function errorText(e: any, fallback: string): string {
  const raw: string = e?.message || fallback;
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed.error === 'string') return parsed.error;
  } catch {
    // not JSON
  }
  return raw;
}

const INPUT_CLASS =
  'w-full px-2 py-1 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500';

export default function PendingTransactionsModal({ documentId, onClose, onImported }: Props) {
  const [rows, setRows] = useState<PendingTransaction[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState('');
  const [result, setResult] = useState<{ imported: number; skipped: number } | null>(null);

  // Last server-confirmed value per row, used to detect changes and revert.
  const persistedRef = useRef<Record<string, PendingTransaction>>({});
  // Always-current copy of rows so async saves read the latest local edits.
  const rowsRef = useRef<PendingTransaction[]>([]);
  // Per-row save chain (serializes saves for one row) and the set of in-flight saves.
  const rowQueueRef = useRef<Record<string, Promise<boolean>>>({});
  const pendingSavesRef = useRef<Set<Promise<boolean>>>(new Set());

  function updateRows(fn: (prev: PendingTransaction[]) => PendingTransaction[]) {
    rowsRef.current = fn(rowsRef.current);
    setRows(rowsRef.current);
  }

  useEffect(() => {
    async function load() {
      try {
        const [pending, cats] = await Promise.all([
          apiJson<PendingTransaction[]>(`/documents/${documentId}/pending-transactions`),
          apiJson<Category[]>('/categories'),
        ]);
        persistedRef.current = Object.fromEntries(pending.map(p => [p.id, p]));
        rowsRef.current = pending;
        setRows(pending);
        setCategories(cats);
        setSelected(new Set(pending.map(p => p.id)));
      } catch (e: any) {
        setLoadError(errorText(e, 'Failed to load pending transactions'));
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [documentId]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape' && !importing && !result) onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [importing, result, onClose]);

  // Local edit (uncommitted until blur).
  function editLocal(id: string, patch: Partial<PendingTransaction>) {
    updateRows(prev => prev.map(r => (r.id === id ? { ...r, ...patch } : r)));
  }

  function setRowError(id: string, message: string) {
    setRowErrors(prev => ({ ...prev, [id]: message }));
  }

  // Revert only the given field of a row to its last confirmed value.
  function revertField(id: string, field: EditableField) {
    const base = persistedRef.current[id];
    if (!base) return;
    updateRows(prev => prev.map(r => (r.id === id ? { ...r, [field]: base[field] } : r)));
  }

  async function runSave(id: string, field: EditableField): Promise<boolean> {
    const current = rowsRef.current.find(r => r.id === id);
    const base = persistedRef.current[id];
    if (!current || !base) return true;
    if (current[field] === base[field] || (field === 'description' && (current.description ?? '') === (base.description ?? ''))) {
      return true;
    }
    if (field === 'amount' && (typeof current.amount !== 'number' || Number.isNaN(current.amount) || current.amount <= 0)) {
      setRowError(id, 'Enter an amount greater than 0');
      revertField(id, field);
      return false;
    }
    if (field === 'transactionDate' && !current.transactionDate) {
      setRowError(id, 'Enter a date');
      revertField(id, field);
      return false;
    }
    setRowError(id, '');
    // Send the confirmed values plus only this field's new value; other dirty
    // fields are saved by their own blur.
    const payload = { ...base, [field]: current[field] };
    try {
      const updated = await apiJson<PendingTransaction>(`/pending-transactions/${id}`, {
        method: 'PUT',
        body: JSON.stringify({
          categoryId: payload.categoryId || null,
          amount: payload.amount,
          type: payload.type,
          description: payload.description || null,
          transactionDate: payload.transactionDate,
        }),
      });
      persistedRef.current[id] = updated;
      updateRows(prev => prev.map(r => (r.id === id ? { ...r, [field]: updated[field] } : r)));
      return true;
    } catch (e: any) {
      revertField(id, field);
      setRowError(id, errorText(e, 'Failed to save'));
      return false;
    }
  }

  function commit(id: string, field: EditableField) {
    const prior = rowQueueRef.current[id] ?? Promise.resolve(true);
    const p: Promise<boolean> = prior.then(() => runSave(id, field));
    rowQueueRef.current[id] = p;
    pendingSavesRef.current.add(p);
    p.finally(() => {
      pendingSavesRef.current.delete(p);
    });
    return p;
  }

  function toggle(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const allSelected = rows.length > 0 && selected.size === rows.length;

  function toggleAll() {
    setSelected(allSelected ? new Set() : new Set(rows.map(r => r.id)));
  }

  async function handleImport() {
    if (importing || selected.size === 0) return;
    setImporting(true);
    setImportError('');
    try {
      // Blur-triggered saves must land before confirming.
      const outcomes = await Promise.all(Array.from(pendingSavesRef.current));
      if (outcomes.some(ok => !ok)) {
        setImportError('Fix the highlighted rows before importing.');
        return;
      }
      const res = await apiJson<{ imported: number; skipped: number }>(
        `/documents/${documentId}/pending-transactions/confirm`,
        { method: 'POST', body: JSON.stringify({ ids: Array.from(selected) }) },
      );
      setResult(res);
    } catch (e: any) {
      setImportError(errorText(e, 'Import failed'));
    } finally {
      setImporting(false);
    }
  }

  const importDisabled = selected.size === 0 || importing;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Review extracted transactions"
        className="bg-white rounded-lg shadow-xl w-full max-w-5xl max-h-[90vh] flex flex-col"
      >
        <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-gray-900">Review extracted transactions</h2>
          {!result && (
            <button
              onClick={onClose}
              disabled={importing}
              className="text-sm text-gray-500 hover:text-gray-700 disabled:opacity-50"
            >
              Cancel
            </button>
          )}
        </div>

        <div className="flex-1 overflow-auto">
          {result ? (
            <div className="p-8 text-center">
              <p className="text-gray-900 font-medium mb-4">
                Imported {result.imported}, skipped {result.skipped} duplicates
              </p>
              <button
                onClick={onImported}
                className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700"
              >
                Close
              </button>
            </div>
          ) : loading ? (
            <div className="p-6 space-y-3 animate-pulse">
              {[0, 1, 2, 3].map(i => <div key={i} className="h-4 bg-gray-200 rounded w-full" />)}
            </div>
          ) : loadError ? (
            <div role="alert" className="m-6 bg-red-50 border border-red-200 rounded-lg p-3 text-red-700 text-sm">{loadError}</div>
          ) : rows.length === 0 ? (
            <div className="p-6 text-center text-gray-500">No pending transactions to review.</div>
          ) : (
            <table className="w-full">
              <thead>
                <tr className="text-left text-sm font-medium text-gray-500 border-b border-gray-100">
                  <th className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={allSelected}
                      onChange={toggleAll}
                      aria-label="Select all"
                    />
                  </th>
                  <th className="px-2 py-3">Date</th>
                  <th className="px-2 py-3">Description</th>
                  <th className="px-2 py-3">Category</th>
                  <th className="px-2 py-3">Type</th>
                  <th className="px-2 py-3">Amount</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <Fragment key={r.id}>
                    <tr className={`align-top ${rowErrors[r.id] ? '' : 'border-b border-gray-50'}`}>
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selected.has(r.id)}
                          onChange={() => toggle(r.id)}
                          aria-label={`Select row ${i + 1}: ${r.description ?? ''}`}
                        />
                      </td>
                      <td className="px-2 py-3">
                        <input
                          type="date"
                          value={r.transactionDate}
                          onChange={e => editLocal(r.id, { transactionDate: e.target.value })}
                          onBlur={() => commit(r.id, 'transactionDate')}
                          className={INPUT_CLASS}
                        />
                      </td>
                      <td className="px-2 py-3">
                        <input
                          type="text"
                          value={r.description ?? ''}
                          onChange={e => editLocal(r.id, { description: e.target.value })}
                          onBlur={() => commit(r.id, 'description')}
                          className={INPUT_CLASS}
                        />
                      </td>
                      <td className="px-2 py-3">
                        <select
                          value={r.categoryId ?? ''}
                          onChange={e => editLocal(r.id, { categoryId: e.target.value || null })}
                          onBlur={() => commit(r.id, 'categoryId')}
                          className={INPUT_CLASS}
                        >
                          <option value="">{r.suggestedCategory ? `None (suggested: ${r.suggestedCategory})` : 'None'}</option>
                          {categories.map(c => (
                            <option key={c.id} value={c.id}>{c.name}</option>
                          ))}
                        </select>
                      </td>
                      <td className="px-2 py-3">
                        <select
                          value={r.type}
                          onChange={e => editLocal(r.id, { type: e.target.value })}
                          onBlur={() => commit(r.id, 'type')}
                          className={INPUT_CLASS}
                        >
                          <option value="EXPENSE">Expense</option>
                          <option value="INCOME">Income</option>
                        </select>
                      </td>
                      <td className="px-2 py-3">
                        <input
                          type="number"
                          step="0.01"
                          min="0.01"
                          value={Number.isNaN(r.amount) ? '' : r.amount}
                          onChange={e => editLocal(r.id, { amount: parseFloat(e.target.value) })}
                          onBlur={() => commit(r.id, 'amount')}
                          className={INPUT_CLASS}
                        />
                      </td>
                    </tr>
                    {rowErrors[r.id] && (
                      <tr className="border-b border-gray-50">
                        <td />
                        <td colSpan={5} role="alert" className="px-2 pb-3 text-xs text-red-600">
                          {rowErrors[r.id]}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {!result && !loading && !loadError && rows.length > 0 && (
          <div className="px-6 py-4 border-t border-gray-200 flex items-center justify-between gap-4">
            <div role="alert" className="text-sm text-red-600">{importError}</div>
            <div className="flex items-center gap-3">
              <span className="text-sm text-gray-500">{selected.size} of {rows.length} selected</span>
              <button
                onClick={handleImport}
                disabled={importDisabled}
                className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-50"
              >
                {importing ? 'Importing...' : 'Import selected'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
