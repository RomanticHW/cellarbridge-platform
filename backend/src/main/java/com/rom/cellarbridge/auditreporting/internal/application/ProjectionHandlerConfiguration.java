package com.rom.cellarbridge.auditreporting.internal.application;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingResult;
import com.rom.cellarbridge.platform.LocalEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ProjectionHandlerConfiguration {

  @Bean
  LocalEventHandler partnerSubmittedProjection(AuditReportingService service) {
    return handler("cellarbridge.partner.submitted-for-review.v1", service);
  }

  @Bean
  LocalEventHandler partnerActivatedProjection(AuditReportingService service) {
    return handler("cellarbridge.partner.activated.v1", service);
  }

  @Bean
  LocalEventHandler partnerChangesProjection(AuditReportingService service) {
    return handler("cellarbridge.partner.changes-requested.v1", service);
  }

  @Bean
  LocalEventHandler partnerRejectedProjection(AuditReportingService service) {
    return handler("cellarbridge.partner.rejected.v1", service);
  }

  @Bean
  LocalEventHandler partnerSuspendedProjection(AuditReportingService service) {
    return handler("cellarbridge.partner.suspended.v1", service);
  }

  @Bean
  LocalEventHandler quotationDraftProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.draft-created.v1", service);
  }

  @Bean
  LocalEventHandler routeEvaluatedProjection(AuditReportingService service) {
    return handler("cellarbridge.trade-planning.route-evaluated.v1", service);
  }

  @Bean
  LocalEventHandler quotationApprovalRequestedProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.approval-requested.v1", service);
  }

  @Bean
  LocalEventHandler quotationApprovedProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.approved.v1", service);
  }

  @Bean
  LocalEventHandler quotationChangesProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.changes-requested.v1", service);
  }

  @Bean
  LocalEventHandler quotationRejectedProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.rejected.v1", service);
  }

  @Bean
  LocalEventHandler quotationIssuedProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.issued.v1", service);
  }

  @Bean
  LocalEventHandler quotationAcceptedProjection(AuditReportingService service) {
    return handler("cellarbridge.quotation.accepted.v1", service);
  }

  @Bean
  LocalEventHandler orderCreatedProjection(AuditReportingService service) {
    return handler("cellarbridge.order.created.v1", service);
  }

  @Bean
  LocalEventHandler reservationConfirmedProjection(AuditReportingService service) {
    return handler("cellarbridge.inventory.reservation-confirmed.v1", service);
  }

  @Bean
  LocalEventHandler reservationFailedProjection(AuditReportingService service) {
    return handler("cellarbridge.inventory.reservation-failed.v1", service);
  }

  @Bean
  LocalEventHandler fulfillmentPlanProjection(AuditReportingService service) {
    return handler("cellarbridge.fulfillment.plan-created.v1", service);
  }

  @Bean
  LocalEventHandler fulfillmentStartedProjection(AuditReportingService service) {
    return handler("cellarbridge.fulfillment.step-started.v1", service);
  }

  @Bean
  LocalEventHandler fulfillmentOverdueProjection(AuditReportingService service) {
    return handler("cellarbridge.fulfillment.step-overdue.v1", service);
  }

  @Bean
  LocalEventHandler fulfillmentFailedProjection(AuditReportingService service) {
    return handler("cellarbridge.fulfillment.step-failed.v1", service);
  }

  @Bean
  LocalEventHandler publicMilestoneProjection(AuditReportingService service) {
    return handler("cellarbridge.fulfillment.public-milestone-reached.v1", service);
  }

  @Bean
  LocalEventHandler fulfillmentCompletedProjection(AuditReportingService service) {
    return handler("cellarbridge.fulfillment.completed.v1", service);
  }

  @Bean
  LocalEventHandler exceptionOpenedProjection(AuditReportingService service) {
    return handler("cellarbridge.exception.opened.v1", service);
  }

  @Bean
  LocalEventHandler exceptionClosedProjection(AuditReportingService service) {
    return handler("cellarbridge.exception.closed.v1", service);
  }

  @Bean
  LocalEventHandler receivableCreatedProjection(AuditReportingService service) {
    return handler("cellarbridge.settlement.receivable-created.v1", service);
  }

  @Bean
  LocalEventHandler paymentRecordedProjection(AuditReportingService service) {
    return handler("cellarbridge.settlement.payment-recorded.v1", service);
  }

  @Bean
  LocalEventHandler paymentReversedProjection(AuditReportingService service) {
    return handler("cellarbridge.settlement.payment-reversed.v1", service);
  }

  @Bean
  LocalEventHandler receivableOverdueProjection(AuditReportingService service) {
    return handler("cellarbridge.settlement.receivable-overdue.v1", service);
  }

  @Bean
  LocalEventHandler receivablePaidProjection(AuditReportingService service) {
    return handler("cellarbridge.settlement.receivable-paid.v1", service);
  }

  private static LocalEventHandler handler(String type, AuditReportingService service) {
    return new ProjectionHandler(type, service);
  }

  private record ProjectionHandler(String eventType, AuditReportingService service)
      implements LocalEventHandler {
    @Override
    public String consumerName() {
      return ProjectionDefinition.PROJECTOR;
    }

    @Override
    public EventHandlingResult handle(EventDelivery delivery) {
      return service.project(delivery);
    }
  }
}
