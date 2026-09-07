package com.finch.domain.ai.relay;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON 키의 snake_case ↔ camelCase 재귀 변환 (apiSpec 10.3). <b>키 표기만 바꾸고 이름·값은 건드리지 않는다</b> —
 * {@code ticker} 를 {@code stockCode} 로 바꾸지 않고, {@code related_tickers} 는 {@code relatedTickers} 가 된다 (contracts C74).
 * <p>
 * 엔드포인트별 DTO 대신 트리 변환인 이유 — 중계는 {@code content} 스키마를 모르는 <b>제네릭 프록시</b>다 (apiSpec 10.3). AI 가 필드를
 * 더해도 백엔드를 고치지 않고 프론트까지 흘러간다. DTO 를 두면 그 순간 백엔드가 AI 스키마의 사본이 되고, 둘이 갈라질 때마다 필드가
 * 조용히 사라진다.
 * <p>
 * 새 트리를 만들어 돌려준다 — 입력을 고치지 않는다. 값이 문자열이면 그 안의 snake 표기는 손대지 않는다(값은 데이터다).
 */
public final class CaseConverter {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private CaseConverter() {
	}

	/** AI 응답 → 프론트. {@code data_as_of} → {@code dataAsOf}. */
	public static JsonNode toCamel(JsonNode node) {
		return convert(node, CaseConverter::camel);
	}

	/** 프론트 요청 → AI. {@code linkedTradeId} → {@code linked_trade_id}. */
	public static JsonNode toSnake(JsonNode node) {
		return convert(node, CaseConverter::snake);
	}

	private static JsonNode convert(JsonNode node, java.util.function.UnaryOperator<String> rename) {
		if (node == null) {
			return null;
		}
		if (node.isObject()) {
			ObjectNode out = NODES.objectNode();
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				out.set(rename.apply(entry.getKey()), convert(entry.getValue(), rename));
			}
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = NODES.arrayNode();
			for (JsonNode child : node) {
				out.add(convert(child, rename));
			}
			return out;
		}
		return node;
	}

	/** {@code a_b_c} → {@code aBC}. 이미 camel 이면 그대로. 앞뒤 밑줄은 유지한다 — {@code _id} 같은 키를 망가뜨리지 않는다. */
	static String camel(String key) {
		if (key.indexOf('_') < 0) {
			return key;
		}
		StringBuilder out = new StringBuilder(key.length());
		boolean upperNext = false;
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			boolean edge = i == 0 || i == key.length() - 1;
			if (c == '_' && !edge) {
				upperNext = true;
				continue;
			}
			out.append(upperNext ? Character.toUpperCase(c) : c);
			upperNext = false;
		}
		return out.toString();
	}

	/** {@code aBC} → {@code a_b_c}. 연속 대문자는 각각 나눈다 ({@code requestID} → {@code request_i_d} 가 아니라 AI 스키마에 그런 키가 없다). */
	static String snake(String key) {
		StringBuilder out = new StringBuilder(key.length() + 4);
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (Character.isUpperCase(c) && i > 0) {
				out.append('_').append(Character.toLowerCase(c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}
