/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.repository.util;

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

public class EntryUtilTest {

	private static final Date EARLY = Date.from(Instant.parse("2020-01-01T00:00:00Z"));
	private static final Date MIDDLE = Date.from(Instant.parse("2023-06-15T12:00:00Z"));
	private static final Date LATE = Date.from(Instant.parse("2026-09-10T00:00:00Z"));

	/**
	 * Both dates are stubbed on every entry so one factory serves both sort methods; each test then
	 * varies only the date it sorts on, which is what pins the extractor to the right getter.
	 */
	private static Entry entry(String id, Date created, Date modified, GraphType graphType) {
		Entry entry = mock(Entry.class);
		lenient().when(entry.getEntryURI()).thenReturn(URI.create("http://localhost:8181/1/entry/" + id));
		lenient().when(entry.getCreationDate()).thenReturn(created);
		lenient().when(entry.getModifiedDate()).thenReturn(modified);
		lenient().when(entry.getGraphType()).thenReturn(graphType);
		return entry;
	}

	private static List<String> idsOf(List<Entry> entries) {
		return entries.stream()
			.map(e -> e == null ? null : URISplit.getLastSegment(e.getEntryURI().toString()))
			.toList();
	}

	@Test
	public void testSortAfterModificationDate() {
		Entry oldest = entry("oldest", LATE, EARLY, GraphType.None);
		Entry newest = entry("newest", EARLY, LATE, GraphType.None);
		Entry middle = entry("middle", MIDDLE, MIDDLE, GraphType.None);
		List<Entry> entries = new ArrayList<>(Arrays.asList(newest, oldest, middle));

		EntryUtil.sortAfterModificationDate(entries, true, null);

		// creation dates are deliberately in the opposite order, so this only passes on the modified date
		assertEquals(List.of("oldest", "middle", "newest"), idsOf(entries));
	}

	@Test
	public void sortAfterModificationDate_descending() {
		Entry oldest = entry("oldest", EARLY, EARLY, GraphType.None);
		Entry newest = entry("newest", LATE, LATE, GraphType.None);
		Entry middle = entry("middle", MIDDLE, MIDDLE, GraphType.None);
		List<Entry> entries = new ArrayList<>(Arrays.asList(oldest, newest, middle));

		EntryUtil.sortAfterModificationDate(entries, false, null);

		assertEquals(List.of("newest", "middle", "oldest"), idsOf(entries));
	}

	@Test
	public void testSortAfterCreationDate() {
		Entry oldest = entry("oldest", EARLY, LATE, GraphType.None);
		Entry newest = entry("newest", LATE, EARLY, GraphType.None);
		Entry middle = entry("middle", MIDDLE, MIDDLE, GraphType.None);
		List<Entry> entries = new ArrayList<>(Arrays.asList(newest, oldest, middle));

		EntryUtil.sortAfterCreationDate(entries, true, null);

		// modification dates are deliberately in the opposite order, so this only passes on the creation date
		assertEquals(List.of("oldest", "middle", "newest"), idsOf(entries));
	}

	@Test
	public void sortAfterCreationDate_descending() {
		Entry oldest = entry("oldest", EARLY, EARLY, GraphType.None);
		Entry newest = entry("newest", LATE, LATE, GraphType.None);
		Entry middle = entry("middle", MIDDLE, MIDDLE, GraphType.None);
		List<Entry> entries = new ArrayList<>(Arrays.asList(oldest, newest, middle));

		EntryUtil.sortAfterCreationDate(entries, false, null);

		assertEquals(List.of("newest", "middle", "oldest"), idsOf(entries));
	}

	@Test
	public void sortAfterDate_prioritizedGraphTypeWinsOverTheDateOrder() {
		Entry newestList = entry("newestList", LATE, LATE, GraphType.List);
		Entry oldestFile = entry("oldestFile", EARLY, EARLY, GraphType.None);
		List<Entry> entries = new ArrayList<>(Arrays.asList(oldestFile, newestList));

		EntryUtil.sortAfterModificationDate(entries, true, GraphType.List);

		// the date sort puts oldestFile first; prioritizeBuiltinType then lifts the List above it
		assertEquals(List.of("newestList", "oldestFile"), idsOf(entries));
	}

	@Test
	public void sortAfterDate_nullEntrySortsAsEqualRatherThanThrowing() {
		Entry oldest = entry("oldest", EARLY, EARLY, GraphType.None);
		Entry newest = entry("newest", LATE, LATE, GraphType.None);
		List<Entry> entries = new ArrayList<>(Arrays.asList(newest, null, oldest));

		EntryUtil.sortAfterModificationDate(entries, true, null);

		// a null compares equal to everything, so the sort completes and keeps every element; where
		// the null lands is not defined, because that comparator is not a total order
		assertEquals(3, entries.size());
		assertTrue(entries.containsAll(Arrays.asList(newest, null, oldest)));
	}

	@Test
	public void sortAfterDate_singleEntryAndEmptyListAreLeftAlone() {
		Entry only = entry("only", MIDDLE, MIDDLE, GraphType.None);
		List<Entry> single = new ArrayList<>(List.of(only));
		List<Entry> empty = new ArrayList<>();

		EntryUtil.sortAfterCreationDate(single, true, null);
		EntryUtil.sortAfterCreationDate(empty, true, null);

		assertEquals(List.of("only"), idsOf(single));
		assertEquals(List.of(), idsOf(empty));
	}

	@Disabled("To be implemented")
	@Test
	public void testSortAfterFileSize() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSortAfterTitle() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testPrioritizeBuiltinType() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetLabel() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetLabel1() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetResource() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetTitle() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetTitle1() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetName() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetStructuredName() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetFirstName() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetLastName() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetEmail() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetMemberOf() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetFOAFTitle() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetTitles() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetDescriptions() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetKeywords() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testIsDeleted() throws Exception {
		// TODO
	}
}
