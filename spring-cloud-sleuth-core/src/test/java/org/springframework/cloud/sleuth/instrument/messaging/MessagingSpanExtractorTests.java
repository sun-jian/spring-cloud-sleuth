/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.messaging;

import org.junit.Test;
import org.springframework.cloud.sleuth.Span;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.cloud.sleuth.assertions.SleuthAssertions.then;

public class MessagingSpanExtractorTests {
	HeaderBasedMessagingExtractor extractor = new HeaderBasedMessagingExtractor();

	@Test
	public void should_return_null_if_trace_or_span_is_missing() {
		then(this.extractor.joinTrace(
				new MessagingTextMap(MessageBuilder.withPayload("")))).isNull();

		then(this.extractor.joinTrace(
				new MessagingTextMap(MessageBuilder.withPayload("").copyHeaders(headers("trace"))))).isNull();
	}

	@Test
	public void should_set_random_traceid_if_header_value_is_invalid() {
		try {
			this.extractor.joinTrace(
					new MessagingTextMap(MessageBuilder.withPayload("")
							.copyHeaders(headers("invalid", randomId()))));
			fail("should throw an exception");
		} catch (IllegalArgumentException e) {
			then(e).hasMessageContaining("Malformed id");
		}
	}

	@Test
	public void should_parse_128bit_trace_id() {
		String traceId128 = "463ac35c9f6413ad48485a3953bb6124";

		Span span = this.extractor.joinTrace(
				new MessagingTextMap(MessageBuilder.withPayload("")
						.copyHeaders(headers(traceId128, randomId()))));

		then(span.traceIdString()).isEqualTo(traceId128);
	}

	@Test
	public void should_propagate_baggage_headers() {
		String traceId128 = "463ac35c9f6413ad48485a3953bb6124";

		Span span = this.extractor.joinTrace(
				new MessagingTextMap(MessageBuilder.withPayload("")
						.copyHeaders(headers(traceId128, randomId()))));

		then(span)
				.hasBaggageItem("foo", "foofoo")
				.hasBaggageItem("bar", "barbar");
		then(span.getBaggageItem("Foo")).isEqualTo("foofoo");
		then(span.getBaggageItem("BAr")).isEqualTo("barbar");
	}

	@Test
	public void should_set_random_spanid_if_header_value_is_invalid() {
		try {
			this.extractor.joinTrace(
					new MessagingTextMap(MessageBuilder.withPayload("")
							.copyHeaders(headers(randomId(), "invalid"))));
			fail("should throw an exception");
		} catch (IllegalArgumentException e) {
			then(e).hasMessageContaining("Malformed id");
		}
	}

	@Test
	public void should_not_throw_exception_if_parent_id_is_invalid() {
		try {
			this.extractor.joinTrace(
					new MessagingTextMap(MessageBuilder.withPayload("")
							.copyHeaders(headers(randomId(), randomId(), "invalid"))));
			fail("should throw an exception");
		} catch (IllegalArgumentException e) {
			then(e).hasMessageContaining("Malformed id");
		}
	}

	private MessageHeaders headers(String traceId) {
		return headers(traceId, null, null);
	}

	private MessageHeaders headers(String traceId, String spanId) {
		return headers(traceId, spanId, null);
	}
	
	private MessageHeaders headers(String traceId, String spanId, String parentId) {
		Map<String, Object> map = new HashMap<>();
		if (StringUtils.hasText(traceId)) {
			map.put(TraceMessageHeaders.TRACE_ID_NAME, traceId);
		}
		if (StringUtils.hasText(spanId)) {
			map.put(TraceMessageHeaders.SPAN_ID_NAME, spanId);
		}
		if (StringUtils.hasText(parentId)) {
			map.put(TraceMessageHeaders.PARENT_ID_NAME, parentId);
		}
		map.put("baggage_foo", "foofoo");
		map.put("BAGGAGE_BAR", "barbar");
		return new MessageHeaders(map);
	}
	
	private String randomId() {
		return String.valueOf(new Random().nextLong());
	}
}