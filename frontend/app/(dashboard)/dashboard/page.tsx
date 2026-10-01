'use client';

import { useEffect, useState } from 'react';
import { apiJson } from '@/lib/api';

interface Summary {
  totalIncome: number;
  totalExpenses: number;
  netBalance: number;
  startDate: string;
  endDate: string;
}

interface Transaction {
  id: string;
  categoryId: string | null;
  amount: number;
  type: string;
  description: string;
  transactionDate: string;
  createdAt: string;
}

function formatCurrency(n: number): string {
  return '$' + Math.abs(n).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function toLocalDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export default function DashboardPage() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    async function load() {
      try {
        const now = new Date();
        const startDate = toLocalDate(new Date(now.getFullYear(), now.getMonth(), 1));
        const endDate = toLocalDate(now);

        const [sum, txns] = await Promise.all([
          apiJson<Summary>(`/transactions/summary?startDate=${startDate}&endDate=${endDate}`),
          apiJson<Transaction[]>('/transactions'),
        ]);
        setSummary(sum);
        setTransactions(txns.slice(0, 5));
      } catch (e: any) {
        setError(e.message || 'Failed to load dashboard');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h1>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          {[0, 1, 2].map(i => (
            <div key={i} className="bg-white rounded-lg shadow-sm p-6 animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
              <div className="h-8 bg-gray-200 rounded w-32" />
            </div>
          ))}
        </div>
        <div className="bg-white rounded-lg shadow-sm p-6 animate-pulse">
          <div className="h-5 bg-gray-200 rounded w-48 mb-4" />
          {[0, 1, 2].map(i => (
            <div key={i} className="h-4 bg-gray-200 rounded w-full mb-3" />
          ))}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div>
        <h1 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h1>
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 text-red-700">{error}</div>
      </div>
    );
  }

  const stats = [
    { label: 'Total Income', value: summary?.totalIncome ?? 0, color: 'border-green-500', textColor: 'text-green-600' },
    { label: 'Total Expenses', value: summary?.totalExpenses ?? 0, color: 'border-red-500', textColor: 'text-red-600' },
    { label: 'Net Balance', value: summary?.netBalance ?? 0, color: 'border-indigo-500', textColor: 'text-indigo-600' },
  ];

  return (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h1>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        {stats.map(s => (
          <div key={s.label} className={`bg-white rounded-lg shadow-sm p-6 border-l-4 ${s.color}`}>
            <p className="text-sm font-medium text-gray-500">{s.label}</p>
            <p className={`text-2xl font-bold mt-1 ${s.textColor}`}>
              {s.value < 0 ? '-' : ''}{formatCurrency(s.value)}
            </p>
          </div>
        ))}
      </div>

      <div className="bg-white rounded-lg shadow-sm">
        <div className="px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-semibold text-gray-900">Recent Transactions</h2>
        </div>
        {transactions.length === 0 ? (
          <div className="p-6 text-center text-gray-500">No transactions yet. Add your first transaction to get started.</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="text-left text-sm font-medium text-gray-500 border-b border-gray-100">
                <th className="px-6 py-3">Date</th>
                <th className="px-6 py-3">Description</th>
                <th className="px-6 py-3">Type</th>
                <th className="px-6 py-3 text-right">Amount</th>
              </tr>
            </thead>
            <tbody>
              {transactions.map(tx => (
                <tr key={tx.id} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-6 py-4 text-sm text-gray-600">{tx.transactionDate}</td>
                  <td className="px-6 py-4 text-sm text-gray-900">{tx.description}</td>
                  <td className="px-6 py-4">
                    <span className={`inline-block px-2 py-1 text-xs font-medium rounded-full ${
                      tx.type === 'INCOME'
                        ? 'bg-green-100 text-green-700'
                        : 'bg-red-100 text-red-700'
                    }`}>
                      {tx.type}
                    </span>
                  </td>
                  <td className={`px-6 py-4 text-sm text-right font-medium ${
                    tx.type === 'INCOME' ? 'text-green-600' : 'text-red-600'
                  }`}>
                    {tx.type === 'INCOME' ? '+' : '-'}{formatCurrency(tx.amount)}
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
