package com.finch.global.apiPayload.code;

import org.springframework.http.HttpStatus;

/**
 * 도메인별 에러 코드 enum 이 구현한다.
 * 코드 목록의 원본은 apiSpec 11장이고, 이 인터페이스는 그것을 코드로 옮긴 형태다.
 */
public interface BaseErrorCode {

	HttpStatus getStatus();

	String getCode();

	String getMessage();
}
