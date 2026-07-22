export const plannedModules = [
  { key: 'audit-reporting', label: 'Audit & reporting', requiredPermission: 'reporting:read' },
  { key: 'notifications', label: 'Notifications', requiredPermission: 'event-publication:read' },
] as const;
