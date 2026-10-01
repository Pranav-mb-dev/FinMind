'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import { apiJson, apiFetch } from '@/lib/api';

interface Document {
  id: string;
  filename: string;
  fileType: string;
  status: string;
  chunksIndexed: number;
  createdAt: string;
}

export default function DocumentsPage() {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const pollIntervalsRef = useRef<Map<string, NodeJS.Timeout>>(new Map());

  useEffect(() => {
    async function load() {
      try {
        setDocuments(await apiJson<Document[]>('/documents'));
      } catch (e: any) {
        setError(e.message || 'Failed to load documents');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  const pollStatus = useCallback((docId: string) => {
    if (pollIntervalsRef.current.has(docId)) return;
    const interval = setInterval(async () => {
      try {
        const updated = await apiJson<Document>(`/documents/${docId}/status`);
        if (updated.status !== 'PROCESSING') {
          clearInterval(interval);
          pollIntervalsRef.current.delete(docId);
        }
        setDocuments(prev => prev.map(d => d.id === docId ? updated : d));
      } catch {
        clearInterval(interval);
        pollIntervalsRef.current.delete(docId);
      }
    }, 3000);
    pollIntervalsRef.current.set(docId, interval);
  }, []);

  useEffect(() => {
    documents.forEach(d => {
      if (d.status === 'PROCESSING') pollStatus(d.id);
    });
    return () => {
      pollIntervalsRef.current.forEach(interval => clearInterval(interval));
      pollIntervalsRef.current.clear();
    };
  }, [documents, pollStatus]);

  async function uploadFile(file: File) {
    const ext = file.name.split('.').pop()?.toLowerCase();
    if (ext !== 'pdf' && ext !== 'docx') {
      setError('Only PDF and DOCX files are supported.');
      return;
    }
    setUploading(true);
    setError('');
    try {
      const formData = new FormData();
      formData.append('file', file);
      const resp = await apiFetch('/documents/upload', { method: 'POST', body: formData });
      if (!resp.ok) {
        const text = await resp.text();
        throw new Error(text || `Upload failed (${resp.status})`);
      }
      const newDoc: Document = await resp.json();
      setDocuments(prev => [newDoc, ...prev]);
    } catch (e: any) {
      setError(e.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragActive(false);
    const file = e.dataTransfer.files[0];
    if (file) uploadFile(file);
  }

  function handleFileInput(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) uploadFile(file);
    e.target.value = '';
  }

  function statusBadge(status: string) {
    switch (status) {
      case 'PROCESSING':
        return <span className="inline-flex items-center px-2.5 py-1 text-xs font-medium rounded-full bg-amber-100 text-amber-700 animate-pulse">Processing</span>;
      case 'READY':
        return <span className="inline-flex items-center px-2.5 py-1 text-xs font-medium rounded-full bg-green-100 text-green-700">Ready</span>;
      case 'FAILED':
        return <span className="inline-flex items-center px-2.5 py-1 text-xs font-medium rounded-full bg-red-100 text-red-700">Failed</span>;
      default:
        return <span className="inline-flex items-center px-2.5 py-1 text-xs font-medium rounded-full bg-gray-100 text-gray-700">{status}</span>;
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Documents</h1>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3 text-red-700 text-sm">{error}</div>
      )}

      <div
        className={`mb-6 border-2 border-dashed rounded-lg p-8 text-center transition-colors cursor-pointer ${
          dragActive ? 'border-indigo-500 bg-indigo-50' : 'border-gray-300 bg-white hover:border-gray-400'
        }`}
        onDragOver={e => { e.preventDefault(); setDragActive(true); }}
        onDragLeave={() => setDragActive(false)}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept=".pdf,.docx"
          className="hidden"
          onChange={handleFileInput}
        />
        {uploading ? (
          <div className="flex flex-col items-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600 mb-3" />
            <p className="text-sm text-gray-600">Uploading...</p>
          </div>
        ) : (
          <div>
            <svg className="mx-auto h-12 w-12 text-gray-400 mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 16v-8m0 0l-3 3m3-3l3 3M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <p className="text-sm text-gray-600">
              <span className="font-medium text-indigo-600">Click to upload</span> or drag and drop
            </p>
            <p className="text-xs text-gray-400 mt-1">PDF or DOCX files only</p>
          </div>
        )}
      </div>

      <div className="bg-white rounded-lg shadow-sm">
        <div className="px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-semibold text-gray-900">Uploaded Documents</h2>
        </div>
        {loading ? (
          <div className="p-6 space-y-3 animate-pulse">
            {[0, 1, 2].map(i => <div key={i} className="h-4 bg-gray-200 rounded w-full" />)}
          </div>
        ) : documents.length === 0 ? (
          <div className="p-6 text-center text-gray-500">No documents uploaded yet.</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="text-left text-sm font-medium text-gray-500 border-b border-gray-100">
                <th className="px-6 py-3">Filename</th>
                <th className="px-6 py-3">Type</th>
                <th className="px-6 py-3">Status</th>
                <th className="px-6 py-3">Chunks</th>
                <th className="px-6 py-3">Uploaded</th>
              </tr>
            </thead>
            <tbody>
              {documents.map(doc => (
                <tr key={doc.id} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-6 py-4 text-sm text-gray-900 font-medium">{doc.filename}</td>
                  <td className="px-6 py-4 text-sm text-gray-600 uppercase">{doc.fileType}</td>
                  <td className="px-6 py-4">{statusBadge(doc.status)}</td>
                  <td className="px-6 py-4 text-sm text-gray-600">{doc.chunksIndexed}</td>
                  <td className="px-6 py-4 text-sm text-gray-600">
                    {new Date(doc.createdAt).toLocaleDateString()}
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
