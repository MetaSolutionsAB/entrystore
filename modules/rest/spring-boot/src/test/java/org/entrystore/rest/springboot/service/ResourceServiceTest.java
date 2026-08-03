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

package org.entrystore.rest.springboot.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.dto.CompletionState;
import org.entrystore.rest.springboot.util.EmailSender;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.json.JSONArray;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.security.SsrfSafeHttpClient;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.entrystore.rest.springboot.util.RDFJSON;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.entrystore.rest.springboot.configuration.ProxyPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

	@TempDir
	Path isolatedTmpDir;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ResourceJsonSerializer resourceSerializer;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private SsrfValidator ssrfValidator;

	@Mock
	private AuthService authService;

	@Mock
	private Entry entry;

	@Mock
	private EmailSender emailSender;

	private ResourceService service;

	@BeforeEach
	void setUp() {
		service = new ResourceService(repositoryManager, resourceSerializer, principalManager, ssrfValidator,
				new SsrfSafeHttpClient(ssrfValidator, ProxyPropertiesFixture.defaults()), authService, emailSender);
		// Point importTmpDir at the JUnit-managed isolated directory so the
		// temp-file cleanup assertions are scoped to this test and cannot be
		// polluted by other processes or orphan files in the shared system temp.
		service.setImportTmpDir(isolatedTmpDir.toFile());
	}

	@Test
	void setEntryResource_userPasswordChange_sendsTheConfirmationEmail() {
		// The only place ResourceService sends mail. It was previously unreachable in this test class,
		// because EmailSender was a literal null, so nothing pinned that a password change notifies the
		// user at all — or that it does so only after the change actually took.
		URI userUri = URI.create("http://example.com/_principals/resource/3");
		User resourceUser = userWithPasswordChangeAllowed(userUri, "Sup3rSecret!", true);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(userUri);

		CompletionState state = service.setEntryResource(entry, passwordBody("Sup3rSecret!"),
				"application/json", "application/json", false, null, "session-1");

		assertEquals(CompletionState.UPDATED, state);
		verify(resourceUser).setSecret("Sup3rSecret!");
		// Own password change, so only the other sessions of this user are expired.
		verify(authService).expireUserSessions(resourceUser, "session-1");
		verify(emailSender).sendPasswordChangeConfirmation(entry);
	}

	@Test
	void setEntryResource_rejectedPassword_sendsNoConfirmationEmail() {
		// setSecret returning false means the password did not change, so a confirmation would tell the
		// user something untrue.
		// No getAuthenticatedUserURI stub: a rejected password throws before session expiry is considered,
		// which is itself worth knowing — nothing is expired and nothing is mailed.
		userWithPasswordChangeAllowed(URI.create("http://example.com/_principals/resource/3"), "weak", false);

		assertThrows(BadRequestException.class, () -> service.setEntryResource(entry, passwordBody("weak"),
				"application/json", "application/json", false, null, "session-1"));

		verifyNoInteractions(emailSender);
		verifyNoInteractions(authService);
	}

	private User userWithPasswordChangeAllowed(URI userUri, String newPassword, boolean accepted) {
		User resourceUser = mock(User.class);
		lenient().when(resourceUser.getURI()).thenReturn(userUri);
		lenient().when(resourceUser.setSecret(newPassword)).thenReturn(accepted);
		when(entry.getGraphType()).thenReturn(GraphType.User);
		when(entry.getResource()).thenReturn(resourceUser);
		Config config = mock(Config.class);
		// Skips the current-password challenge, which is a separate branch with its own coverage.
		when(config.getBoolean(Settings.AUTH_PASSWORD_REQUIRE_CURRENT_PASSWORD, true)).thenReturn(false);
		when(repositoryManager.getConfiguration()).thenReturn(config);
		when(repositoryManager.getPrincipalManager()).thenReturn(principalManager);
		return resourceUser;
	}

	private static byte[] passwordBody(String password) {
		return ("{\"password\":\"" + password + "\"}").getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void importEntryResource_zipWithoutRdfEntries_throwsNotImplementedAndDeletesTempFile() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);
		byte[] zipBytes = buildZip("readme.txt", "hello");

		// No .rdf entries to import, so importFromZIP returns and importEntryResource throws
		// NotImplementedException afterwards; the temp file must still be cleaned up.
		assertThrows(NotImplementedException.class,
				() -> service.importEntryResource(entry, zipBytes));

		assertIsolatedTmpDirIsEmpty("not-implemented path, no rdf entries");
	}

	@Test
	void importEntryResource_nonListGraphType_throwsBadRequest() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.None);
		byte[] zipBytes = buildZip("data.rdf", "<rdf:RDF/>");

		// The else branch rejects non-List entries before any ZIP is read.
		assertThrows(BadRequestException.class,
				() -> service.importEntryResource(entry, zipBytes));

		assertIsolatedTmpDirIsEmpty("non-List graphType rejected before importFromZIP");
	}

	@Test
	void importEntryResource_zipWithRdfEntry_deletesTempFileEvenOnException() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);
		byte[] zipBytes = buildZip("data.rdf", "<rdf:RDF/>");

		// importRDFResource is currently stubbed and throws NotImplementedException;
		// the finally block in importFromZIP must still clean up.
		assertThrows(NotImplementedException.class,
				() -> service.importEntryResource(entry, zipBytes));

		assertIsolatedTmpDirIsEmpty("exception path from importRDFResource stub");
	}

	@Test
	void deleteResource_proxyTrueUnauthorized_throwsBeforeOutboundDelete() {
		// Pins the contract that the WriteResource check fires BEFORE the proxy branch
		// reaches deleteRemoteResource — otherwise an unauthorized caller could trigger
		// an outbound DELETE.
		doThrow(new AuthorizationException(null, null, AccessProperty.WriteResource))
				.when(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		assertThrows(AuthorizationException.class,
				() -> service.deleteResource(entry, "true", false));

		verifyNoInteractions(ssrfValidator);
	}

	@Test
	void deleteResource_proxyTrue_blacklistedOrigin_throwsForbidden() throws Exception {
		when(entry.getEntryType()).thenReturn(EntryType.Link);
		when(entry.getResourceURI()).thenReturn(URI.create("http://127.0.0.1:1/x"));
		doThrow(new ForbiddenException("Access denied: host is blacklisted"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(ForbiddenException.class,
				() -> service.deleteResource(entry, "true", false));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void deleteResource_proxyTrue_badScheme_throwsBadRequest() throws Exception {
		when(entry.getEntryType()).thenReturn(EntryType.Reference);
		when(entry.getResourceURI()).thenReturn(URI.create("ftp://example.org/x"));
		doThrow(new BadRequestException("Only http and https URLs are supported"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(BadRequestException.class,
				() -> service.deleteResource(entry, "true", false));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void deleteResource_proxyTrue_userinfoInUrl_throwsBadRequest() throws Exception {
		when(entry.getEntryType()).thenReturn(EntryType.LinkReference);
		when(entry.getResourceURI()).thenReturn(URI.create("http://user:pass@example.org/x"));
		doThrow(new BadRequestException("URLs with embedded credentials are not allowed"))
				.when(ssrfValidator).validateForDelete(anyString());

		assertThrows(BadRequestException.class,
				() -> service.deleteResource(entry, "true", false));

		verify(ssrfValidator, never()).openPinnedConnection(any(), any());
	}

	@Test
	void importEntryResource_malformedZipPayload_deletesTempFileAndThrows() throws IOException {
		when(entry.getGraphType()).thenReturn(GraphType.List);
		byte[] notAZip = "this is not a zip file".getBytes(StandardCharsets.UTF_8);

		// new ZipFile(tmpFile) throws ZipException (an IOException) for a non-ZIP
		// payload; importFromZIP wraps it as InternalServerErrorException.
		InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
				() -> service.importEntryResource(entry, notAZip));
		assertInstanceOf(ZipException.class, ex.getCause());

		assertIsolatedTmpDirIsEmpty("exception path from ZipFile constructor");
	}

	// The isList && application/json short-circuit must not swallow a non-list Graph resource: that case goes
	// to GraphUtil.serializeGraph, which routes application/json through RDFJSON. Before ENTRYSTORE-1091 the
	// service called RDFJSON.graphToRdfJson directly here, so nothing pinned the rerouted arm.
	@Test
	void serializeResourceAsJson_graphResourceAsJson_returnsRdfJsonNotIdArray() {
		ValueFactory vf = SimpleValueFactory.getInstance();
		Model graph = new LinkedHashModel();
		graph.add(vf.createIRI("http://example.com/s"), vf.createIRI("http://purl.org/dc/terms/title"),
				vf.createLiteral("Sample"));
		RDFResource resource = mock(RDFResource.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.Graph);
		when(entry.getResource()).thenReturn(resource);
		when(resource.getGraph()).thenReturn(graph);

		String result = service.serializeResourceAsJson(entry, "application/json", new ListFilter(null, null, null, null, null, null, null));

		// Routing: RDF/JSON, not the id array the isList branch would have produced.
		assertEquals(RDFJSON.graphToRdfJson(graph), result);
		// Content, asserted independently of RDFJSON: were graphToRdfJson to regress to an empty object,
		// both sides of the equality above would move together and still match.
		assertTrue(result.contains("http://purl.org/dc/terms/title"),
				"Expected the predicate IRI in the RDF/JSON output");
		assertTrue(result.contains("Sample"), "Expected the literal value in the RDF/JSON output");
	}

	@Test
	void serializeResourceAsJson_listWithSort_returnsSortedIdArray() {
		Context context = mockListEntry(List.of("a", "b", "c"));
		mockResolvableChild(context, "a", new Date(3000));
		mockResolvableChild(context, "b", new Date(1000));
		mockResolvableChild(context, "c", new Date(2000));
		var filter = new ListFilter("modified", null, null, null, null, null, null);

		String result = service.serializeResourceAsJson(entry, "application/json", filter);

		assertEquals(List.of("b", "c", "a"), jsonArrayToList(result));
	}

	@Test
	void serializeResourceAsJson_listSortDescending_returnsReversedIdArray() {
		// Pins the !"desc".equalsIgnoreCase(order) mapping in ListParams.withoutPagination.
		Context context = mockListEntry(List.of("a", "b", "c"));
		mockResolvableChild(context, "a", new Date(3000));
		mockResolvableChild(context, "b", new Date(1000));
		mockResolvableChild(context, "c", new Date(2000));
		var filter = new ListFilter("modified", null, null, null, "desc", null, null);

		String result = service.serializeResourceAsJson(entry, "application/json", filter);

		assertEquals(List.of("a", "c", "b"), jsonArrayToList(result));
	}

	@Test
	void serializeResourceAsJson_listOver500Children_returnsUnsortedIds() {
		// Above 500 children the sort branch is skipped entirely: the raw (HashSet-ordered,
		// nondeterministic) ID set is returned and no child entry is resolved.
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 501; i++) {
			ids.add("id" + i);
		}
		mockListEntry(ids);
		var filter = new ListFilter("modified", null, null, null, null, null, null);

		String result = service.serializeResourceAsJson(entry, "application/json", filter);

		List<String> returned = jsonArrayToList(result);
		assertEquals(501, returned.size());
		assertEquals(new HashSet<>(ids), new HashSet<>(returned));
	}

	@Test
	void serializeResourceAsJson_listSortedBranch_missingChildSkipped() {
		// "b" is referenced by the list but does not resolve in the context — it is logged
		// and dropped from the sorted output (unlike the unsorted branch, which keeps raw IDs).
		Context context = mockListEntry(List.of("a", "b", "c"));
		mockResolvableChild(context, "a", new Date(1000));
		when(context.get("b")).thenReturn(null);
		mockResolvableChild(context, "c", new Date(2000));
		var filter = new ListFilter("modified", null, null, null, null, null, null);

		String result = service.serializeResourceAsJson(entry, "application/json", filter);

		assertEquals(List.of("a", "c"), jsonArrayToList(result));
	}

	@Test
	void serializeResourceAsJson_listWithSort_ignoresNonNumericOffsetAndLimit() {
		// The sorting path ignores offset/limit, so malformed values must not fail the request
		// (ListParams.withoutPagination skips the Integer.parseInt done by ListParams(ListFilter)).
		Context context = mockListEntry(List.of("a", "b"));
		mockResolvableChild(context, "a", new Date(2000));
		mockResolvableChild(context, "b", new Date(1000));
		var filter = new ListFilter("modified", null, null, null, null, "abc", "xyz");

		String result = service.serializeResourceAsJson(entry, "application/json", filter);

		assertEquals(List.of("b", "a"), jsonArrayToList(result));
	}

	/** Stubs {@code entry} as a List-type entry whose list resource references the given child IDs. */
	private Context mockListEntry(List<String> childIds) {
		Context context = mock(Context.class);
		org.entrystore.List list = mock(org.entrystore.List.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getGraphType()).thenReturn(GraphType.List);
		when(entry.getResource()).thenReturn(list);
		when(list.getGraph()).thenReturn(new LinkedHashModel());
		lenient().when(entry.getContext()).thenReturn(context);
		List<URI> childUris = new ArrayList<>();
		for (String id : childIds) {
			childUris.add(URI.create("http://example.com/ctx/entry/" + id));
		}
		when(list.getChildren()).thenReturn(childUris);
		return context;
	}

	private void mockResolvableChild(Context context, String id, Date modified) {
		Entry child = mock(Entry.class);
		lenient().when(child.getEntryURI()).thenReturn(URI.create("http://example.com/ctx/entry/" + id));
		lenient().when(child.getModifiedDate()).thenReturn(modified);
		lenient().when(context.get(id)).thenReturn(child);
	}

	private static List<String> jsonArrayToList(String json) {
		JSONArray array = new JSONArray(json);
		List<String> values = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			values.add(array.getString(i));
		}
		return values;
	}

	private void assertIsolatedTmpDirIsEmpty(String pathDescription) throws IOException {
		try (Stream<Path> entries = Files.list(isolatedTmpDir)) {
			assertEquals(0, entries.count(),
					"isolated tmpdir must be empty after importEntryResource (" + pathDescription + ")");
		}
	}

	private static byte[] buildZip(String entryName, String body) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(baos)) {
			zos.putNextEntry(new ZipEntry(entryName));
			zos.write(body.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return baos.toByteArray();
	}
}
