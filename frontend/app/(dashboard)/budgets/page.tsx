'use client';

import { useEffect, useState } from 'react';
import { apiJson, apiFetch } from '@/lib/api';

interface Budget {
  id: string;
  categoryId: string;
  amount: number;
  period: string;
  startDate: string;
  endDate: string;
  createdAt: string;
}

interface Category {
  id: string;
  name: string;
  icon: string;
  isSystem: boolean;
}

function formatCurrency(n: number): string {
  return '$' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function todayStr(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const EMPTY_FORM = { categoryId: '', amount: '', period: 'MONTHLY', startDate: todayStr(), endDate: '' };

export default function BudgetsPage() {
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    async function load() {
      try {
        const [b, c] = await Promise.all([
          apiJson<Budget[]>('/budgets'),
          apiJson<Category[]>('/categories'),
        ]);
        setBudgets(b);
        setCategories(c);
      } catch (e: any) {
        setError(e.message || 'Failed to load');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  function categoryName(id: string): string {
    return categories.find(c => c.id === id)?.name ?? 'Unknown';
  }

  function openCreate() {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setShowForm(true);
  }

  function openEdit(b: Budget) {
    setEditingId(b.id);
    setForm({
      categoryId: b.categoryId,
      amount: String(b.amount),
      period: b.period,
      startDate: b.startDate,
      endDate: b.endDate,
    });
    setShowForm(true);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError('');
    const body = JSON.stringify({
      categoryId: form.categoryId,
      amount: parseFloat(form.amount),
      period: form.period,
      startDate: form.startDate,
      endDate: form.endDate,
    });
    try {
      if (editingId) {
        await apiJson(`/budgets/${editingId}`, { method: 'PUT', body });
      } else {
        await apiJson('/budgets', { method: 'POST', body });
      }
      setBudgets(await apiJson<Budget[]>('/budgets'));
      setShowForm(false);
      setEditingId(null);
      setForm(EMPTY_FORM);
    } catch (e: any) {
      setError(e.message || 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete(id: string) {
    try {
      await apiFetch(`/budgets/${id}`, { method: 'DELETE' });
      setBudgets(prev => prev.filter(b => b.id !== id));
    } catch (e: any) {
      setError(e.message || 'Delete failed');
    }
  }

  const periodColors: Record<string, string> = {
    WEEKLY: 'bg-blue-100 text-blue-700',
    MONTHLY: 'bg-purple-100 text-purple-700',
    YEARLY: 'bg-teal-100 text-teal-700',
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Budgets</h1>
        <button
          onClick={() => showForm ? (setShowForm(false), setEditingId(null)) : openCreate()}
          className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700"
        >
          {showForm ? 'Cancel' : 'Add Budget'}
        </button>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3 text-red-700 text-sm">{error}</div>
      )}

      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white rounded-lg shadow-sm p-6 mb-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">{editingId ? 'Edit Budget' : 'New Budget'}</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Category</label>
              <select
                required
                value={form.categoryId}
                onChange={e => setForm(f => ({ ...f, categoryId: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              >
                <option value="">Select category</option>
                {categories.map(c => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </div>
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
              <label className="block text-sm font-medium text-gray-700 mb-1">Period</label>
              <select
                value={form.period}
                onChange={e => setForm(f => ({ ...f, period: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              >
                <option value="WEEKLY">Weekly</option>
                <option value="MONTHLY">Monthly</option>
                <option value="YEARLY">Yearly</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Start Date</label>
              <input
                type="date"
                required
                value={form.startDate}
                onChange={e => setForm(f => ({ ...f, startDate: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">End Date</label>
              <input
                type="date"
                required
                value={form.endDate}
                onChange={e => setForm(f => ({ ...f, endDate: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              />
            </div>
            <div className="flex items-end">
              <button
                type="submit"
                disabled={submitting}
                className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-50"
              >
                {submitting ? 'Saving...' : editingId ? 'Update' : 'Create'}
              </button>
            </div>
          </div>
        </form>
      )}

      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[0, 1, 2].map(i => (
            <div key={i} className="bg-white rounded-lg shadow-sm p-6 animate-pulse">
              <div className="h-5 bg-gray-200 rounded w-32 mb-3" />
              <div className="h-8 bg-gray-200 rounded w-24 mb-3" />
              <div className="h-4 bg-gray-200 rounded w-full" />
            </div>
          ))}
        </div>
      ) : budgets.length === 0 ? (
        <div className="bg-white rounded-lg shadow-sm p-8 text-center text-gray-500">
          No budgets yet. Create one to start tracking your spending limits.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {budgets.map(b => (
            <div key={b.id} className="bg-white rounded-lg shadow-sm p-6">
              <div className="flex items-start justify-between mb-3">
                <h3 className="text-lg font-semibold text-gray-900">{categoryName(b.categoryId)}</h3>
                <span className={`px-2 py-1 text-xs font-medium rounded-full ${periodColors[b.period] || 'bg-gray-100 text-gray-700'}`}>
                  {b.period}
                </span>
              </div>
              <p className="text-2xl font-bold text-indigo-600 mb-3">{formatCurrency(b.amount)}</p>
              <p className="text-sm text-gray-500 mb-4">{b.startDate} to {b.endDate}</p>
              <div className="flex gap-2">
                <button
                  onClick={() => openEdit(b)}
                  className="px-3 py-1.5 text-sm text-indigo-600 hover:bg-indigo-50 rounded-lg"
                >
                  Edit
                </button>
                <button
                  onClick={() => handleDelete(b.id)}
                  className="px-3 py-1.5 text-sm text-red-500 hover:bg-red-50 rounded-lg"
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
