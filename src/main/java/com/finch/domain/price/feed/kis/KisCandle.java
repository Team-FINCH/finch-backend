package com.finch.domain.price.feed.kis;

import java.time.LocalDate;

/** 기간별시세 응답({@code FHKST03010100})의 일봉 한 줄. 최신순으로 오지만 {@code KisClient} 가 오래된 날부터로 뒤집어 준다. */
public record KisCandle(LocalDate tradeDate, long open, long high, long low, long close, long volume) {
}
