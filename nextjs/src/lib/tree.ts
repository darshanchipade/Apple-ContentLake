export type TreeNode = {
  id: string;
  label: string;
  path: string;
  type: "object" | "array" | "value";
  children?: TreeNode[];
  value?: unknown;
};

const MAX_TREE_NODES = 5000;
const MAX_ARRAY_CHILDREN = 100;

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

export const buildTreeFromJson = (
  payload: unknown,
  parentPath: string[] = [],
  counter: { value: number } = { value: 0 },
): TreeNode[] => {
  if (counter.value >= MAX_TREE_NODES) return [];

  if (Array.isArray(payload)) {
    return payload.slice(0, MAX_ARRAY_CHILDREN).flatMap((entry, index) => {
      const label = `[${index}]`;
      const id = [...parentPath, label].join(".");
      counter.value += 1;
      if (counter.value >= MAX_TREE_NODES) return [];

      const childNodes = buildTreeFromJson(entry, [...parentPath, label], counter);
      return [
        {
          id,
          label,
          path: id,
          type: Array.isArray(entry)
            ? "array"
            : isPlainObject(entry)
              ? "object"
              : "value",
          children: childNodes.length ? childNodes : undefined,
          value:
            !Array.isArray(entry) && !isPlainObject(entry)
              ? entry
              : childNodes.length === 0
                ? entry
                : undefined,
        },
      ];
    });
  }

  if (isPlainObject(payload)) {
    return Object.entries(payload).flatMap(([key, value]) => {
      if (counter.value >= MAX_TREE_NODES) return [];
      const id = [...parentPath, key].join(".");
      counter.value += 1;
      const childNodes = buildTreeFromJson(value, [...parentPath, key], counter);
      return [
        {
          id,
          label: key,
          path: id,
          type: Array.isArray(value)
            ? "array"
            : isPlainObject(value)
              ? "object"
              : "value",
          children: childNodes.length ? childNodes : undefined,
          value:
            !Array.isArray(value) && !isPlainObject(value)
              ? value
              : childNodes.length === 0
                ? value
                : undefined,
        },
      ];
    });
  }

  return [];
};

export const gatherLeafNodes = (node: TreeNode): TreeNode[] => {
  if (!node.children || node.children.length === 0) {
    return [node];
  }
  return node.children.flatMap((child) => gatherLeafNodes(child));
};

export const gatherNodeIds = (node: TreeNode): string[] => {
  return [
    node.id,
    ...(node.children?.flatMap((child) => gatherNodeIds(child)) ?? []),
  ];
};

export const filterTree = (nodes: TreeNode[], query: string): TreeNode[] => {
  if (!query) return nodes;
  const normalized = query.toLowerCase();

  const searchNode = (node: TreeNode): TreeNode | null => {
    const matches = node.label.toLowerCase().includes(normalized);
    if (!node.children || node.children.length === 0) {
      return matches ? node : null;
    }
    const filteredChildren = node.children
      .map(searchNode)
      .filter((child): child is TreeNode => Boolean(child));
    if (matches || filteredChildren.length > 0) {
      return {
        ...node,
        children: filteredChildren.length ? filteredChildren : undefined,
      };
    }
    return null;
  };

  return nodes
    .map(searchNode)
    .filter((node): node is TreeNode => Boolean(node));
};
