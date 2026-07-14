export const plannedModules = [
  { key: 'fulfillment', label: 'Fulfillment', requiredPermission: 'fulfillment:read' },
  { key: 'exception-center', label: 'Exception center', requiredPermission: 'exception:read' },
  { key: 'settlement', label: 'Settlement', requiredPermission: 'settlement:read' },
  { key: 'audit-reporting', label: 'Audit & reporting', requiredPermission: 'reporting:read' },
  { key: 'notifications', label: 'Notifications', requiredPermission: 'event-publication:read' },
] as const;
