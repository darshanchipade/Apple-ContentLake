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
      <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-3 px-8 py-8">
        {eyebrow && (
          <span className="text-[10px] font-bold uppercase tracking-[0.35em] text-slate-400">
            {eyebrow}
          </span>
        )}
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div className="space-y-2">
            <h1 className="text-3xl font-bold text-black">{title}</h1>
            {description && (
              <p className="text-sm font-medium text-slate-500 lg:max-w-2xl">{description}</p>
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