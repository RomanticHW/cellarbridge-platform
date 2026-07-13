export const plannedModules = [
  { key: 'partners', label: 'Partners', requiredPermission: 'partner:read' },
  { key: 'catalog', label: 'Catalog', requiredPermission: 'catalog:read' },
  { key: 'inventory', label: 'Inventory', requiredPermission: 'inventory:read' },
  { key: 'quotations', label: 'Quotations', requiredPermission: 'quotation:read' },
  { key: 'trade-planning', label: 'Trade planning', requiredPermission: 'quotation:read' },
  { key: 'trade-orders', label: 'Trade orders', requiredPermission: 'order:read' },
  { key: 'fulfillment', label: 'Fulfillment', requiredPermission: 'fulfillment:read' },
  { key: 'exception-center', label: 'Exception center', requiredPermission: 'exception:read' },
  { key: 'settlement', label: 'Settlement', requiredPermission: 'settlement:read' },
  { key: 'audit-reporting', label: 'Audit & reporting', requiredPermission: 'reporting:read' },
  { key: 'notifications', label: 'Notifications', requiredPermission: 'event-publication:read' },
] as const;
