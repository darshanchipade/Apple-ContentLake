import type { ReactNode } from "react";

type StageHeroProps = {
  title: string;
  description?: string;
  eyebrow?: string;
  actionsSlot?: ReactNode;
};

export function StageHero({ title, description, eyebrow, actionsSlot }: StageHeroProps) {
  return (
    <section className="border-b border-slate-200 bg-background">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-3 px-4 py-8 lg:px-6 lg:py-10">
        {eyebrow && (
          <span className="text-[10px] lg:text-xs font-semibold uppercase tracking-[0.35em] text-slate-400">
            {eyebrow}
          </span>
        )}
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div className="space-y-3">
            <h1 className="text-2xl lg:text-4xl font-semibold text-slate-900">{title}</h1>
            {description && (
              <p className="text-base text-slate-500 lg:max-w-2xl">{description}</p>
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