export const plannedModules = [
  { key: 'settlement', label: 'Settlement', requiredPermission: 'settlement:read' },
  { key: 'audit-reporting', label: 'Audit & reporting', requiredPermission: 'reporting:read' },
  { key: 'notifications', label: 'Notifications', requiredPermission: 'event-publication:read' },
] as const;
