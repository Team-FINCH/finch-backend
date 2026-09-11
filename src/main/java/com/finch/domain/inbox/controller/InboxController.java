package com.finch.domain.inbox.controller;

import com.finch.domain.inbox.dto.response.InboxRes;
import com.finch.domain.inbox.service.InboxService;
import com.finch.global.apiPayload.code.GeneralErrorCode;
import com.finch.global.exception.CustomException;
import com.finch.global.security.LoginUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림함 API (apiSpec 6.4). 고유 에러 코드가 없다 — 목록은 항목이 없어도 200 이고, 읽음 표시는 대상이 없어도 204 다.
 */
@RestController
@RequestMapping("/api/v1/inbox")
@RequiredArgsConstructor
public class InboxController {

	/** apiSpec 6.4 · {@code inbox_read.item_id VARCHAR(64)}. 넘으면 DB 까지 가지 않고 400 이다. */
	static final int MAX_ITEM_ID_LENGTH = 64;

	private final InboxService inboxService;

	@GetMapping
	public InboxRes list(@LoginUser long userId) {
		return inboxService.list(userId);
	}

	@PostMapping("/{itemId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markRead(@LoginUser long userId, @PathVariable String itemId) {
		if (itemId.length() > MAX_ITEM_ID_LENGTH) {
			throw new CustomException(GeneralErrorCode.INVALID_REQUEST,
				Map.of("itemId", MAX_ITEM_ID_LENGTH + "자를 넘을 수 없습니다"));
		}
		inboxService.markRead(userId, itemId);
	}
}
