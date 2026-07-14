package com.rom.cellarbridge.tradeorder.internal.web;

import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderApplicationService;
import com.rom.cellarbridge.tradeorder.internal.web.TradeOrderWebMapper.OrderDetailResponse;
import com.rom.cellarbridge.tradeorder.internal.web.TradeOrderWebMapper.OrderPageResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
final class TradeOrderController {

  private final TradeOrderApplicationService service;

  TradeOrderController(TradeOrderApplicationService service) {
    this.service = service;
  }

  @GetMapping
  OrderPageResponse list(
      @RequestParam(required = false) Set<TradeOrderStatus> status,
      @RequestParam(required = false) UUID partnerId,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return TradeOrderWebMapper.internalPage(
        service.listInternal(status, partnerId, pageSize, cursor));
  }

  @GetMapping("/{orderId}")
  ResponseEntity<OrderDetailResponse> get(@PathVariable UUID orderId) {
    OrderDetailResponse response = TradeOrderWebMapper.internalDetail(service.getInternal(orderId));
    return ResponseEntity.ok().eTag("\"" + response.version() + "\"").body(response);
  }
}
