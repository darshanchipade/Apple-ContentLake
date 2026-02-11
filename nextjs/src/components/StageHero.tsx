import type { ReactNode } from "react";

type StageHeroProps = {
  title: string;
  description?: string;
  eyebrow?: string;
  actionsSlot?: ReactNode;
};

export function StageHero({ title, description, eyebrow, actionsSlot }: StageHeroProps) {
  return (
    <section className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-3 px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
        {eyebrow && (
          <span className="text-[9px] sm:text-[10px] font-bold uppercase tracking-[0.35em] text-slate-400">
            {eyebrow}
          </span>
        )}
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div className="space-y-1 sm:space-y-2">
            <h1 className="text-2xl sm:text-3xl font-bold text-black">{title}</h1>
            {description && (
              <p className="text-xs sm:text-sm font-medium text-slate-500 lg:max-w-2xl">{description}</p>
            )}
          </div>
          {actionsSlot && (
            <div className="flex items-end justify-start lg:justify-end">{actionsSlot}</div>
          )}
        </div>
      </div>
    </section>
  );
}