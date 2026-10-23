import ThemeToggle from '@/components/theme-toggle';

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <ThemeToggle className="fixed top-4 right-4 text-gray-500 hover:text-gray-700" />
      <div className="w-full max-w-md">
        {children}
      </div>
    </div>
  );
}
