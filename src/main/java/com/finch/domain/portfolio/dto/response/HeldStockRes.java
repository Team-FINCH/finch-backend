package com.finch.domain.portfolio.dto.response;

/**
 * 보유 중인 종목 하나 — 시세·평가 없이 코드와 이름만. 알림함(apiSpec 6.4)처럼 "무엇을 들고 있나" 만 알면 되는 쪽이 쓴다.
 * {@code PortfolioRes} 를 쓰지 않는 이유는 {@code PortfolioQueryService.heldStocks} 주석에 있다.
 */
public record HeldStockRes(String stockCode, String stockName) {
}
