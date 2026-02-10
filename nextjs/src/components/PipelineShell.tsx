import {
  ArrowPathRoundedSquareIcon,
  ArrowTrendingUpIcon,
  CloudArrowUpIcon,
  HomeModernIcon,
  MagnifyingGlassIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import Link from "next/link";
import type { ComponentType, ReactNode, SVGProps } from "react";
import { PipelineTracker, type StepId } from "@/components/PipelineTracker";

type PipelineShellProps = {
  currentStep: StepId;
  showTracker?: boolean;
  children: ReactNode;
};

const workspaceLinks = [
  { label: "Workspace Overview", href: "/ingestion", icon: HomeModernIcon },
  { label: "Upload Activity", href: "/ingestion/activity", icon: CloudArrowUpIcon },
  { label: "Pipeline Health", href: "/extraction", icon: ArrowTrendingUpIcon },
  { label: "Search Finder", href: "/search", icon: MagnifyingGlassIcon },
];

export function PipelineShell({ currentStep, showTracker = true, children }: PipelineShellProps) {
  return (
    <div className="flex min-h-screen bg-[#f7f9fb] text-slate-900">
      <aside className="sticky top-0 hidden h-screen w-72 flex-col border-r border-slate-200 bg-white/90 px-6 py-8 shadow-[20px_0_45px_rgba(15,23,42,0.06)] backdrop-blur lg:flex">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-slate-900 text-2xl text-white shadow-[0_15px_40px_rgba(15,23,42,0.3)]">
            
          </div>
          <div className="leading-tight">
            <p className="text-[0.80rem] font-semibold uppercase tracking-[0.80em] text-slate-900">
              Content
            </p>
            <p className="text-lg font-semibold text-slate-900">Lake</p>
          </div>
        </div>

        <nav className="mt-10 flex flex-1 flex-col gap-8 text-sm">
          <NavSection title="Workspace" links={workspaceLinks} />
        </nav>

        <div className="space-y-4 text-xs text-slate-500">
          <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
            <p className="font-semibold uppercase tracking-[0.2em] text-slate-400">Storage</p>
            <div className="mt-3 flex items-end justify-between">
              <p className="text-3xl font-semibold text-slate-900">82%</p>
              <span className="text-[0.65rem] font-semibold uppercase tracking-[0.25em]">
                Used
              </span>
            </div>
            <div className="mt-3 h-2 rounded-full bg-white">
              <span className="block h-full rounded-full bg-slate-900" style={{ width: "82%" }} />
            </div>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
            <p className="text-[0.65rem] font-semibold uppercase tracking-[0.25em] text-slate-400">
              Need help?
            </p>
            <p className="mt-1 text-sm text-slate-900">Talk with a pipeline specialist.</p>
            <button className="mt-3 inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-slate-900 px-3 py-2 text-xs font-semibold tracking-wide text-slate-900 transition hover:bg-slate-900 hover:text-white">
              <ArrowPathRoundedSquareIcon className="size-4" />
              Contact Support
            </button>
          </div>
        </div>
      </aside>

      <div className="flex-1">
        <div className="flex items-center justify-between border-b border-slate-200 bg-white/95 px-4 py-4 text-sm font-semibold text-slate-900 shadow-sm lg:hidden">
          <div className="flex items-center gap-2">
            <span>Content Lake</span>
            <span className="text-[0.65rem] uppercase tracking-[0.35em] text-slate-400">· Workflow</span>
          </div>
          <span>{currentStep}</span>
        </div>
        <div className="relative">
          {showTracker && (
            <div className="sticky top-0 z-30 border-b border-slate-200 bg-[#f7f9fb]/90 backdrop-blur">
              <div className="mx-auto max-w-6xl px-6 py-6">
                <PipelineTracker current={currentStep} />
              </div>
            </div>
          )}
          <div>{children}</div>
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