package com.rom.cellarbridge.tradeorder.internal.web;

import com.rom.cellarbridge.tradeorder.TradeOrderStatus;
import com.rom.cellarbridge.tradeorder.internal.application.TradeOrderApplicationService;
import com.rom.cellarbridge.tradeorder.internal.web.TradeOrderWebMapper.BuyerOrderDetailResponse;
import com.rom.cellarbridge.tradeorder.internal.web.TradeOrderWebMapper.BuyerOrderPageResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/buyer/orders")
final class BuyerTradeOrderController {

  private final TradeOrderApplicationService service;

  BuyerTradeOrderController(TradeOrderApplicationService service) {
    this.service = service;
  }

  @GetMapping
  BuyerOrderPageResponse list(
      @RequestParam(required = false) Set<TradeOrderStatus> status,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return TradeOrderWebMapper.buyerPage(service.listBuyer(status, pageSize, cursor));
  }

  @GetMapping("/{orderId}")
  BuyerOrderDetailResponse get(@PathVariable UUID orderId) {
    return TradeOrderWebMapper.buyerDetail(service.getBuyer(orderId));
  }
}
