/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

/**
 * A code point the model streams across two chunks reaches the client as that code point, on every
 * channel that carries model text raw (issue #438).
 *
 * <p>Each chunk becomes one frame, and each frame is encoded on its own — so a chunk ending in the
 * high half of a surrogate pair and a chunk opening with the low half used to be two separate
 * encodes of two unpaired surrogates, each replaced by {@code ?}. The clinician saw {@code ??} where
 * the model wrote a non-BMP character, until {@code done} replaced the text. The chunkings below are
 * the ones a delta boundary produces: a boundary inside the pair, a chunk that is nothing BUT the
 * high half, and a chunk whose low half closes one pair while its own end opens the next.
 *
 * <p>Every assertion reads the wire through {@link SseEvents}, so it is about what a client decodes
 * rather than about what the controller passed to its writer. → ADR Decision 101.
 */
public class ChartSearchAiSseSurrogatePairTest {

	/** U+1F489, SYRINGE: one code point, two UTF-16 chars. */
	private static final String SYRINGE = "💉";

	/** The same code point as the four bytes UTF-8 encodes it to. */
	private static final byte[] SYRINGE_UTF8 = { (byte) 0xF0, (byte) 0x9F, (byte) 0x92, (byte) 0x89 };

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		out = new ByteArrayOutputStream();
	}

	@Test
	public void aCodePointSplitAcrossTwoAnswerChunksReachesTheClientWhole() throws Exception {
		stream(Channel.TOKEN, "dose \uD83D", "\uDC89 given");

		assertChannelCarries("token", "dose " + SYRINGE + " given");
	}

	@Test
	public void aCodePointSplitAcrossTwoThinkingChunksReachesTheClientWhole() throws Exception {
		stream(Channel.THINKING, "dose \uD83D", "\uDC89 given");

		assertChannelCarries("thinking", "dose " + SYRINGE + " given");
	}

	@Test
	public void aCodePointSplitAcrossTwoPreliminaryChunksReachesTheClientWhole() throws Exception {
		stream(Channel.PRELIMINARY, "dose \uD83D", "\uDC89 given");

		assertChannelCarries("preliminary", "dose " + SYRINGE + " given");
	}

	/**
	 * A chunk that is ONLY the high half — the shape char-by-char chunking produces for every non-BMP
	 * character — writes no frame of its own: a frame carrying nothing but half a character is the
	 * {@code ?} this class exists to refuse.
	 */
	@Test
	public void aChunkHoldingOnlyTheHighHalfWritesNoFrameOfItsOwn() throws Exception {
		stream(Channel.TOKEN, "dose ", "\uD83D", "\uDC89", " given");

		assertChannelCarries("token", "dose " + SYRINGE + " given");
		assertEquals(Arrays.asList("dose ", SYRINGE, " given"), dataOf("token"),
				"the chunk holding only the high half must be carried INTO the next frame, not framed "
						+ "on its own");
	}

	/** The low half's chunk closes one pair and opens the next, and both arrive whole. */
	@Test
	public void aChunkClosingOnePairAndOpeningTheNextCarriesBoth() throws Exception {
		stream(Channel.TOKEN, "a\uD83D", "\uDC89b\uD83D", "\uDC89c");

		assertChannelCarries("token", "a" + SYRINGE + "b" + SYRINGE + "c");
	}

	/** Each channel holds its own half: a thinking chunk between the two halves of an answer's pair. */
	@Test
	public void aHalfHeldOnOneChannelIsNotCompletedByAnother() throws Exception {
		controller.setChartSearchService(new ChunkingStubService(Arrays.asList(
				new Chunk(Channel.TOKEN, "dose \uD83D"),
				new Chunk(Channel.THINKING, "\uDC89"),
				new Chunk(Channel.TOKEN, "\uDC89 given"))));

		controller.streamAnswer(out, StreamingChartSearchStub.PATIENT, StreamingChartSearchStub.QUESTION,
				RestControllerContext.user(), false);

		assertChannelCarries("token", "dose " + SYRINGE + " given");
	}

	private void stream(Channel channel, String... chunks) {
		List<Chunk> script = new ArrayList<Chunk>();
		for (String chunk : chunks) {
			script.add(new Chunk(channel, chunk));
		}
		controller.setChartSearchService(new ChunkingStubService(script));
		controller.streamAnswer(out, StreamingChartSearchStub.PATIENT, StreamingChartSearchStub.QUESTION,
				RestControllerContext.user(), false);
	}

	private void assertChannelCarries(String type, String expected) {
		SseEvents.assertEveryFrameIsWellFormed(out);
		StringBuilder streamed = new StringBuilder();
		for (String data : dataOf(type)) {
			streamed.append(data);
		}
		assertEquals(expected, streamed.toString(),
				"the '" + type + "' events must carry every code point the model streamed; got "
						+ SseEvents.quoted(streamed.toString()));
		assertFalse(streamed.toString().contains("?"),
				"no half of a surrogate pair may reach the client as the encoder's '?'");
		assertTrue(indexOf(out.toByteArray(), SYRINGE_UTF8) >= 0,
				"the wire must carry U+1F489 as its four UTF-8 bytes");
	}

	private List<String> dataOf(String type) {
		List<String> data = new ArrayList<String>();
		for (SseEvent event : SseEvents.parse(out)) {
			if (event.type.equals(type)) {
				data.add(event.data);
			}
		}
		return data;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private enum Channel { TOKEN, THINKING, PRELIMINARY }

	private static final class Chunk {

		final Channel channel;

		final String text;

		Chunk(Channel channel, String text) {
			this.channel = channel;
			this.text = text;
		}
	}

	/** Streams a script of chunks, each on its channel and in order, then answers normally. */
	private static final class ChunkingStubService implements ChartSearchService {

		private final List<Chunk> script;

		ChunkingStubService(List<Chunk> script) {
			this.script = script;
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return StreamingChartSearchStub.answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			return searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
					citationsConsumer, ungroundedAnswerConsumer, p -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer,
				Consumer<String> preliminaryReasoningConsumer) {
			for (Chunk chunk : script) {
				switch (chunk.channel) {
					case TOKEN:
						tokenConsumer.accept(chunk.text);
						break;
					case THINKING:
						reasoningConsumer.accept(chunk.text);
						break;
					default:
						preliminaryReasoningConsumer.accept(chunk.text);
						break;
				}
			}
			citationsConsumer.accept(Collections.<RecordReference> emptyList());
			return StreamingChartSearchStub.answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
