"use client";

import {
  ArrowUpTrayIcon,
  BeakerIcon,
  DocumentMagnifyingGlassIcon,
  SparklesIcon,
  CircleStackIcon,
  CheckIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import type { ComponentType, SVGProps } from "react";

export type StepId = "ingestion" | "extraction" | "cleansing" | "enrichment";

export type StepMeta = {
  id: StepId;
  label: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
};

export const STEPS: StepMeta[] = [
  { id: "ingestion", label: "Ingestion", icon: ArrowUpTrayIcon },
  { id: "extraction", label: "Extraction", icon: DocumentMagnifyingGlassIcon },
  { id: "cleansing", label: "Cleansing", icon: SparklesIcon },
  { id: "enrichment", label: "Data Enrichment", icon: BeakerIcon },
];

const statusStyles = {
  done: {
    circle: "border-primary bg-primary text-white",
    label: "text-gray-900",
    connector: "bg-primary",
    iconColor: "text-white",
  },
  current: {
    circle: "border-primary bg-primary text-white ring-4 ring-primary-soft",
    label: "text-gray-900 font-bold",
    connector: "bg-gray-100",
    iconColor: "text-white",
  },
  upcoming: {
    circle: "border-gray-100 bg-white text-gray-300",
    label: "text-gray-400",
    connector: "bg-gray-100",
    iconColor: "text-gray-300",
  },
};

export function PipelineTracker({ current }: { current: StepId }) {
  const currentIndex = Math.max(
    0,
    STEPS.findIndex((step) => step.id === current),
  );

  return (
    <nav className="w-full overflow-hidden">
      <div className="flex items-center justify-between gap-1 sm:gap-2 lg:gap-4">
        {STEPS.map((step, index) => {
          const status =
            index < currentIndex
              ? "done"
              : index === currentIndex
                ? "current"
                : "upcoming";
          const isUpcoming = index > currentIndex;
          const styles = statusStyles[status as keyof typeof statusStyles];
          const Icon = step.icon;

          return (
            <div
              key={step.id}
              className={clsx(
                "flex items-center gap-2 lg:gap-4",
                index < STEPS.length - 1 && "flex-1"
              )}
            >
              <div className="flex flex-col items-center gap-1.5 lg:gap-3 relative shrink-0 max-w-[60px] sm:max-w-none">
                <div
                  className={clsx(
                    "flex size-8 sm:size-9 lg:size-14 items-center justify-center rounded-full border-2 transition-all duration-200 relative",
                    styles.circle
                  )}
                >
                  <Icon className={clsx("size-3.5 sm:size-4 lg:size-6", styles.iconColor)} />
                  {status === "done" && (
                     <div className="absolute -right-1 -bottom-1 bg-white rounded-full p-0.5 border border-primary">
                        <CheckIcon className="size-2 sm:size-3 text-primary stroke-[3px]" />
                     </div>
                  )}
                  {status === "current" && (
                     <div className="absolute -right-1 -bottom-1 bg-white rounded-full p-0.5 border border-primary">
                        <div className="size-2 sm:size-3 flex items-center justify-center">
                           <div className="size-1 sm:size-1.5 bg-primary rounded-full animate-pulse" />
                        </div>
                     </div>
                  )}
                </div>
                <span className={clsx("text-[7px] sm:text-[9px] lg:text-xs text-center font-bold uppercase tracking-tighter lg:tracking-normal leading-tight", styles.label)}>
                  {step.label}
                </span>
              </div>

              {index < STEPS.length - 1 && (
                <div className="flex-1 h-px bg-gray-100" />
              )}
            </div>
          );
        })}
      </div>
    </nav>
  );
}