"use client";

import {
  ArrowUpTrayIcon,
  BeakerIcon,
  DocumentMagnifyingGlassIcon,
  SparklesIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import type { ComponentType, SVGProps } from "react";

export type StepId = "ingestion" | "extraction" | "cleansing" | "enrichment";

type StepMeta = {
  id: StepId;
  label: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
};

const STEPS: StepMeta[] = [
  { id: "ingestion", label: "Ingestion", icon: ArrowUpTrayIcon },
  { id: "extraction", label: "Extraction", icon: DocumentMagnifyingGlassIcon },
  { id: "cleansing", label: "Cleansing", icon: SparklesIcon },
  { id: "enrichment", label: "Data Enrichment", icon: BeakerIcon },
];

const statusStyles = {
  done: {
    circle: "border-slate-900 bg-slate-900 text-white shadow-[0_8px_20px_rgba(15,23,42,0.25)]",
    label: "text-slate-900",
    connector: "bg-slate-900",
  },
  current: {
    circle:
      "border-slate-900 bg-slate-900 text-white shadow-[0_8px_20px_rgba(15,23,42,0.25)] scale-105",
    label: "text-slate-900",
    connector: "bg-slate-900",
  },
  upcoming: {
    circle: "border-slate-200 bg-white text-slate-400",
    label: "text-slate-400",
    connector: "bg-slate-200",
  },
};

export function PipelineTracker({ current }: { current: StepId }) {
  const currentIndex = Math.max(
    0,
    STEPS.findIndex((step) => step.id === current),
  );

  return (
    <nav className="w-full text-xs">
      <div className="rounded-[28px] border border-slate-200 bg-white px-6 py-5 shadow-[0_20px_50px_rgba(15,23,42,0.08)]">
        <div className="flex flex-col gap-6">
          <div className="flex items-center justify-between text-[0.65rem] font-semibold uppercase tracking-[0.3em] text-slate-400">
            <span>Pipeline</span>
            <span>
              Step {Math.min(currentIndex + 1, STEPS.length)} of {STEPS.length}
            </span>
          </div>
          <div className="flex flex-col gap-6 md:flex-row md:items-center">
            {STEPS.map((step, index) => {
              const status =
                index < currentIndex
                  ? "done"
                  : index === currentIndex
                    ? "current"
                    : "upcoming";
              const styles = statusStyles[status as keyof typeof statusStyles];
              const Icon = step.icon;
              const connectorActive = index < currentIndex;

              return (
                <div
                  key={step.id}
                  className={clsx(
                    "flex items-center gap-4 md:flex-1",
                    index === STEPS.length - 1 && "md:flex-initial",
                  )}
                >
                  <div className="flex flex-col items-center gap-3 text-center">
                    <div
                      className={clsx(
                        "flex size-14 items-center justify-center rounded-full border-2 transition-transform duration-200",
                        styles.circle,
                      )}
                    >
                      <Icon className="size-5" />
                    </div>
                    <span className={clsx("text-[0.7rem] font-semibold", styles.label)}>
                      {step.label}
                    </span>
                  </div>
                  {index < STEPS.length - 1 && (
                    <div className="hidden flex-1 md:block">
                      <div className="relative h-[2px] w-full rounded-full bg-slate-200">
                        <span
                          className={clsx(
                            "absolute inset-y-0 left-0 rounded-full transition-all duration-300",
                            connectorActive && styles.connector,
                          )}
                          style={{ width: connectorActive ? "100%" : "0%" }}
                        />
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </nav>
  );
}