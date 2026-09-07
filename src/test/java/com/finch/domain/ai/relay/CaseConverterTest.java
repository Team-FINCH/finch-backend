package com.finch.domain.ai.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 키 표기 변환. 이름·값은 바뀌지 않고 표기만 바뀐다 (apiSpec 10.3, contracts C74). */
class CaseConverterTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	@DisplayName("snake → camel 을 중첩 객체·배열까지 재귀로 바꾸고, 값 안의 밑줄과 필드 이름(ticker)은 건드리지 않는다")
	void toCamelRecursively() {
		JsonNode in = mapper.readTree("""
			{"request_id":"req_1","data_as_of":{"price":"t"},"content":{"ticker":"005930","related_tickers":["000660"],
			 "findings":[{"evidence":{"metric_source":"dart"}}],"one_liner":"a_b stays"}}""");

		JsonNode out = CaseConverter.toCamel(in);

		assertThat(out.get("requestId").asString()).isEqualTo("req_1");
		assertThat(out.get("dataAsOf").get("price").asString()).isEqualTo("t");
		assertThat(out.get("content").get("ticker").asString()).isEqualTo("005930");
		assertThat(out.get("content").get("relatedTickers").get(0).asString()).isEqualTo("000660");
		assertThat(out.get("content").get("findings").get(0).get("evidence").get("metricSource").asString()).isEqualTo("dart");
		assertThat(out.get("content").get("oneLiner").asString()).isEqualTo("a_b stays");
		assertThat(out.has("request_id")).isFalse();
		// 입력은 그대로다.
		assertThat(in.has("request_id")).isTrue();
	}

	@Test
	@DisplayName("camel → snake 도 재귀이고, 이미 snake 인 키는 그대로다")
	void toSnakeRecursively() {
		JsonNode in = mapper.readTree("""
			{"requestId":"r","linkedTradeId":7,"orders":[{"stockCode":"005930","side":"BUY"}],"already_snake":1}""");

		JsonNode out = CaseConverter.toSnake(in);

		assertThat(out.get("request_id").asString()).isEqualTo("r");
		assertThat(out.get("linked_trade_id").asInt()).isEqualTo(7);
		assertThat(out.get("orders").get(0).get("stock_code").asString()).isEqualTo("005930");
		assertThat(out.get("already_snake").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("앞뒤 밑줄과 밑줄 없는 키는 그대로다")
	void edges() {
		assertThat(CaseConverter.camel("_id")).isEqualTo("_id");
		assertThat(CaseConverter.camel("plain")).isEqualTo("plain");
		assertThat(CaseConverter.camel("data_as_of")).isEqualTo("dataAsOf");
		assertThat(CaseConverter.snake("dataAsOf")).isEqualTo("data_as_of");
		assertThat(CaseConverter.snake("plain")).isEqualTo("plain");
	}
}
