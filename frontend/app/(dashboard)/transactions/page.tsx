'use client';

import { useEffect, useState } from 'react';
import { apiJson, apiFetch } from '@/lib/api';

interface Transaction {
  id: string;
  categoryId: string | null;
  amount: number;
  type: string;
  description: string;
  transactionDate: string;
  createdAt: string;
}

interface Category {
  id: string;
  name: string;
  icon: string;
  isSystem: boolean;
}

function formatCurrency(n: number): string {
  return '$' + Math.abs(n).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function todayStr(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const EMPTY_FORM = { amount: '', type: 'EXPENSE', description: '', transactionDate: todayStr(), categoryId: '' };

export default function TransactionsPage() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filterStart, setFilterStart] = useState('');
  const [filterEnd, setFilterEnd] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);

  async function fetchTransactions(startDate?: string, endDate?: string) {
    let url = '/transactions';
    if (startDate && endDate) url += `?startDate=${startDate}&endDate=${endDate}`;
    return apiJson<Transaction[]>(url);
  }

  useEffect(() => {
    async function load() {
      try {
        const [txns, cats] = await Promise.all([
          fetchTransactions(),
          apiJson<Category[]>('/categories'),
        ]);
        setTransactions(txns);
        setCategories(cats);
      } catch (e: any) {
        setError(e.message || 'Failed to load');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  async function handleFilter() {
    if (!filterStart || !filterEnd) return;
    setLoading(true);
    setError('');
    try {
      setTransactions(await fetchTransactions(filterStart, filterEnd));
    } catch (e: any) {
      setError(e.message || 'Filter failed');
    } finally {
      setLoading(false);
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError('');
    try {
      await apiJson('/transactions', {
        method: 'POST',
        body: JSON.stringify({
          amount: parseFloat(form.amount),
          type: form.type,
          description: form.description,
          transactionDate: form.transactionDate,
          categoryId: form.categoryId || null,
        }),
      });
      setForm(EMPTY_FORM);
      setShowForm(false);
      setTransactions(await fetchTransactions(filterStart || undefined, filterEnd || undefined));
    } catch (e: any) {
      setError(e.message || 'Failed to create');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete(id: string) {
    try {
      await apiFetch(`/transactions/${id}`, { method: 'DELETE' });
      setTransactions(prev => prev.filter(t => t.id !== id));
    } catch (e: any) {
      setError(e.message || 'Delete failed');
    }
  }

  function categoryName(id: string | null): string {
    if (!id) return '-';
    return categories.find(c => c.id === id)?.name ?? '-';
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Transactions</h1>
        <button
          onClick={() => setShowForm(v => !v)}
          className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700"
        >
          {showForm ? 'Cancel' : 'Add Transaction'}
        </button>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3 text-red-700 text-sm">{error}</div>
      )}

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white rounded-lg shadow-sm p-6 mb-6">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Amount</label>
              <input
                type="number"
                step="0.01"
                min="0.01"
                required
                value={form.amount}
                onChange={e => setForm(f => ({ ...f, amount: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Type</label>
              <select
                value={form.type}
                onChange={e => setForm(f => ({ ...f, type: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              >
                <option value="EXPENSE">Expense</option>
                <option value="INCOME">Income</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
              <input
                type="text"
                required
                value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Date</label>
              <input
                type="date"
                required
                value={form.transactionDate}
                onChange={e => setForm(f => ({ ...f, transactionDate: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Category</label>
              <select
                value={form.categoryId}
                onChange={e => setForm(f => ({ ...f, categoryId: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              >
                <option value="">None</option>
                {categories.map(c => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </div>
            <div className="flex items-end">
              <button
                type="submit"
                disabled={submitting}
                className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-50"
              >
                {submitting ? 'Saving...' : 'Save'}
              </button>
            </div>
          </div>
        </form>
      )}

      <div className="bg-white rounded-lg shadow-sm mb-6 p-4">
        <div className="flex items-center gap-3">
          <input
            type="date"
            value={filterStart}
            onChange={e => setFilterStart(e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
          />
          <span className="text-gray-400">to</span>
          <input
            type="date"
            value={filterEnd}
            onChange={e => setFilterEnd(e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
          />
          <button
            onClick={handleFilter}
            className="px-4 py-2 bg-gray-100 text-gray-700 text-sm font-medium rounded-lg hover:bg-gray-200"
          >
            Filter
          </button>
          {(filterStart || filterEnd) && (
            <button
              onClick={() => { setFilterStart(''); setFilterEnd(''); fetchTransactions().then(setTransactions); }}
              className="px-3 py-2 text-sm text-gray-500 hover:text-gray-700"
            >
              Clear
            </button>
          )}
        </div>
      </div>

      <div className="bg-white rounded-lg shadow-sm">
        {loading ? (
          <div className="p-6 space-y-3 animate-pulse">
            {[0, 1, 2, 3].map(i => <div key={i} className="h-4 bg-gray-200 rounded w-full" />)}
          </div>
        ) : transactions.length === 0 ? (
          <div className="p-6 text-center text-gray-500">No transactions found.</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="text-left text-sm font-medium text-gray-500 border-b border-gray-100">
                <th className="px-6 py-3">Date</th>
                <th className="px-6 py-3">Description</th>
                <th className="px-6 py-3">Category</th>
                <th className="px-6 py-3">Type</th>
                <th className="px-6 py-3 text-right">Amount</th>
                <th className="px-6 py-3" />
              </tr>
            </thead>
            <tbody>
              {transactions.map(tx => (
                <tr key={tx.id} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-6 py-4 text-sm text-gray-600">{tx.transactionDate}</td>
                  <td className="px-6 py-4 text-sm text-gray-900">{tx.description}</td>
                  <td className="px-6 py-4 text-sm text-gray-600">{categoryName(tx.categoryId)}</td>
                  <td className="px-6 py-4">
                    <span className={`inline-block px-2 py-1 text-xs font-medium rounded-full ${
                      tx.type === 'INCOME' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                    }`}>{tx.type}</span>
                  </td>
                  <td className={`px-6 py-4 text-sm text-right font-medium ${
                    tx.type === 'INCOME' ? 'text-green-600' : 'text-red-600'
                  }`}>
                    {tx.type === 'INCOME' ? '+' : '-'}{formatCurrency(tx.amount)}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <button
                      onClick={() => handleDelete(tx.id)}
                      className="text-red-500 hover:text-red-700 text-sm"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
