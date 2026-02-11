import {
  ArrowPathRoundedSquareIcon,
  ArrowTrendingUpIcon,
  CloudArrowUpIcon,
  HomeModernIcon,
  MagnifyingGlassIcon,
  ChevronRightIcon,
  Bars3Icon,
  XMarkIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ComponentType, type ReactNode, type SVGProps, useState, useEffect } from "react";
import { PipelineTracker, type StepId } from "@/components/PipelineTracker";

type PipelineShellProps = {
  currentStep: StepId;
  showTracker?: boolean;
  children: ReactNode;
  breadcrumbExtra?: string;
};

const workspaceLinks = [
  { label: "Workspace Overview", href: "/ingestion", icon: HomeModernIcon },
  { label: "Upload Activity", href: "/ingestion/activity", icon: CloudArrowUpIcon },
  { label: "Pipeline Health", href: "/extraction", icon: ArrowTrendingUpIcon },
  { label: "Search Finder", href: "/search", icon: MagnifyingGlassIcon },
];

export function PipelineShell({
  currentStep,
  showTracker = true,
  children,
  breadcrumbExtra,
}: PipelineShellProps) {
  const pathname = usePathname();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const activeLink = workspaceLinks.find((link) => pathname === link.href);

  // Close mobile menu on path change
  useEffect(() => {
    setIsMobileMenuOpen(false);
  }, [pathname]);

  return (
    <div className="flex min-h-screen bg-white text-slate-900">
      {/* Mobile Menu Overlay */}
      <div
        className={clsx(
          "fixed inset-0 z-50 bg-black/50 transition-opacity lg:hidden",
          isMobileMenuOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        onClick={() => setIsMobileMenuOpen(false)}
      />

      {/* Mobile Sidebar */}
      <aside
        className={clsx(
          "fixed inset-y-0 left-0 z-50 w-72 transform bg-white px-6 py-8 transition-transform duration-300 ease-in-out lg:hidden",
          isMobileMenuOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-black text-xl text-white">
              
            </div>
            <p className="font-bold text-black">Content Lake</p>
          </div>
          <button onClick={() => setIsMobileMenuOpen(false)}>
            <XMarkIcon className="size-6 text-slate-500" />
          </button>
        </div>
        <nav className="mt-10 flex flex-col gap-8 text-sm">
          <NavSection title="Workspaces" links={workspaceLinks} />
        </nav>
      </aside>

      <aside className="sticky top-0 hidden h-screen w-72 flex-col border-r border-slate-200 bg-white px-6 py-8 lg:flex">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-black text-2xl text-white">
            
          </div>
          <div className="leading-tight">
            <p className="text-[0.80rem] font-semibold uppercase tracking-[0.80em] text-black">
              Content
            </p>
            <p className="text-lg font-semibold text-black">Lake</p>
          </div>
        </div>

        <nav className="mt-10 flex flex-1 flex-col gap-8 text-sm">
          <NavSection title="Workspaces" links={workspaceLinks} />
        </nav>

        <div className="space-y-4 text-xs text-slate-500">
          <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
            <p className="font-semibold uppercase tracking-[0.2em] text-slate-400">Storage</p>
            <div className="mt-3 flex items-end justify-between">
              <p className="text-3xl font-semibold text-black">82%</p>
              <span className="text-[0.65rem] font-semibold uppercase tracking-[0.25em]">
                Used
              </span>
            </div>
            <div className="mt-3 h-2 rounded-full bg-white">
              <span className="block h-full rounded-full bg-black" style={{ width: "82%" }} />
            </div>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
            <p className="text-[0.65rem] font-semibold uppercase tracking-[0.25em] text-slate-400">
              Need help?
            </p>
            <p className="mt-1 text-sm text-black">Talk with a pipeline specialist.</p>
            <button className="mt-3 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-black px-3 py-2 text-xs font-semibold tracking-wide text-black transition hover:bg-black hover:text-white">
              <ArrowPathRoundedSquareIcon className="size-4" />
              Contact Support
            </button>
          </div>
        </div>
      </aside>

      <div className="flex-1">
        {/* Breadcrumb Header - Hidden on mobile, replaced by mobile header */}
        <header className="hidden h-16 items-center gap-2 border-b border-slate-200 bg-white px-8 text-sm text-slate-500 lg:flex">
          <span className="font-medium">Workspaces</span>
          <ChevronRightIcon className="size-3 text-slate-400" />
          <span className={clsx("font-medium", !breadcrumbExtra && "text-black")}>
            {activeLink?.label ?? "Delta"}
          </span>
          {breadcrumbExtra && (
            <>
              <ChevronRightIcon className="size-3 text-slate-400" />
              <span className="font-semibold text-black">{breadcrumbExtra}</span>
            </>
          )}
        </header>

        {/* Mobile Header */}
        <div className="sticky top-0 z-40 flex items-center justify-between border-b border-slate-200 bg-white/80 px-4 py-4 text-sm font-semibold text-black shadow-sm backdrop-blur-md lg:hidden">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsMobileMenuOpen(true)}
              className="rounded-lg p-1 hover:bg-slate-100 transition-colors"
            >
              <Bars3Icon className="size-6 text-black" />
            </button>
            <div className="flex items-center gap-2">
              <span className="font-bold">Content Lake</span>
              <span className="text-[10px] uppercase tracking-widest text-slate-400">
                {currentStep}
              </span>
            </div>
          </div>
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-black text-white text-xs">
            
          </div>
        </div>

        <div className="relative bg-[#F9FAFB]">
          {showTracker && (
            <div className="sticky top-[65px] z-30 border-b border-slate-200 bg-[#F9FAFB]/90 backdrop-blur lg:top-0">
              <div className="mx-auto max-w-6xl px-4 py-4 sm:px-6 sm:py-6">
                <PipelineTracker current={currentStep} />
              </div>
            </div>
          )}
          <div className="min-h-[calc(100vh-4rem)]">{children}</div>
        </div>
      </div>
    </div>
  );
}

type NavLink = {
  label: string;
  href: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
};

function NavSection({ title, links }: { title: string; links: NavLink[] }) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-[0.35em] text-slate-400">{title}</p>
      <div className="mt-3 space-y-1.5">
        {links.map((link) => (
          <Link
            key={link.href}
            href={link.href}
            className="flex items-center gap-3 rounded-2xl px-3 py-2 font-semibold text-slate-500 transition hover:text-slate-900"
          >
            <link.icon className="size-4 text-slate-900" />
            {link.label}
          </Link>
        ))}
      </div>
    </div>
  );
}