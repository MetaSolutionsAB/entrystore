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

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.QuotaException;
import org.entrystore.impl.ListImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.ResourceJsonSerializer;
import org.json.JSONArray;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resource operations on List and Group entries: their children (a list of entry URIs, or a group's members),
 * moving an entry between lists, and the not-yet-implemented ZIP import.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListResourceService {

	private final RepositoryManagerImpl repositoryManager;

	@Value("${entrystore.import.tmpdir:${java.io.tmpdir}}")
	@Setter(AccessLevel.PACKAGE)
	private File importTmpDir;

	/**
	 * Replaces the children of a List or Group entry with the entry ids given as a JSON array of strings, resolved
	 * in the entry's own context. Unknown ids and malformed JSON are bad requests; a child listed twice is a
	 * conflict.
	 */
	public void setChildrenFromJson(Entry entry, byte[] requestBody) {
		try {
			JSONArray childrenJSONArray = new JSONArray(new String(requestBody, StandardCharsets.UTF_8));
			ArrayList<URI> newResource = new ArrayList<>();

			// Add new entries to the list.
			for (int i = 0; i < childrenJSONArray.length(); i++) {
				String childId = childrenJSONArray.get(i).toString();
				Entry childEntry = entry.getContext().get(childId);
				if (childEntry == null) {
					throw new BadRequestException("Cannot update resource, since one of the children does not exist. ChildId: " + childId);
				} else {
					newResource.add(childEntry.getEntryURI());
				}
			}

			if (entry.getGraphType() == GraphType.List) {
				org.entrystore.List resourceList = (org.entrystore.List) entry.getResource();
				resourceList.setChildren(newResource);
			} else {
				Group resourceGroup = (Group) entry.getResource();
				resourceGroup.setChildren(newResource);
			}
		} catch (JSONException e) {
			throw new BadRequestException("Cannot parse given body resource as JSONArray.");
		} catch (IllegalArgumentException iae) {
			throw new BadRequestException(iae.getMessage(), iae); // Core exception — message is safe to return
		} catch (RepositoryException re) {
			throw new DataConflictException("An entry cannot be added multiple times", re);
		}
	}

	/**
	 * Replaces the graph of a List or Group entry with the RDF in the request body, serialized as
	 * {@code mediaType}. Any other graph type is a bad request; a member listed twice is a conflict.
	 */
	public void setGraph(Entry entry, byte[] requestBody, String mediaType) {
		Model graph = GraphUtil.deserializeGraph(new String(requestBody, StandardCharsets.UTF_8), mediaType);
		try {
			if (entry.getGraphType() == GraphType.List) {
				((org.entrystore.List) entry.getResource()).setGraph(graph);
			} else if (entry.getGraphType() == GraphType.Group) {
				((Group) entry.getResource()).setGraph(graph);
			} else {
				throw new BadRequestException("Unsupported graph type for RDF graph update: " + entry.getGraphType());
			}
		} catch (IllegalArgumentException iae) {
			throw new BadRequestException(iae.getMessage(), iae); // Core exception — message is safe to return
		} catch (RepositoryException e) {
			throw new DataConflictException("An entry cannot be added multiple times", e);
		}
	}

	/**
	 * Moves an entry from the list {@code fromList} into the list {@code entry}. Both arguments may be absolute
	 * URIs or paths relative to the repository base URL. Returns the moved entry.
	 */
	public Entry moveEntry(Entry entry, String moveEntry, String fromList, boolean removeAll) {

		GraphType graphType = entry.getGraphType();

		if (graphType == GraphType.List
				&& moveEntry != null
				&& fromList != null) {

			// POST 3/resource/45?moveEntry=2/entry/34&fromList=2/resource/67
			ListImpl dest = (ListImpl) entry.getResource();

			String baseURI = repositoryManager.getRepositoryURL().toString();
			if (!baseURI.endsWith("/")) {
				baseURI += "/";
			}

			// Entry URI of the Entry to be moved
			URI movableEntry = resolveAgainstBase(moveEntry, baseURI);
			// Resource URI of the source List
			URI movableEntrySource = resolveAgainstBase(fromList, baseURI);

			Entry movedEntry;
			try {
				movedEntry = dest.moveEntryHere(movableEntry, movableEntrySource, removeAll);
			} catch (QuotaException qe) {
				throw new EntityTooLargeException(qe.getMessage(), qe);
			}

			return movedEntry;
		} else {
			throw new BadRequestException("Bad request: supports only Entry graphType of List (given: " + graphType + ") and moving Entry with 'moveEntry' and 'fromList' parameters.");
		}
	}

	private static URI resolveAgainstBase(String value, String baseURI) {
		URI candidate = URI.create(value);
		return candidate.isAbsolute() ? candidate : URI.create(baseURI + value);
	}

	/**
	 * Imports the RDF files of a ZIP archive into a List entry. Not implemented yet: a readable archive always ends
	 * in {@link NotImplementedException}, an unreadable one in {@link InternalServerErrorException}; other graph
	 * types are a bad request.
	 */
	public void importFromZip(Entry entry, byte[] requestBody) {

		GraphType graphType = entry.getGraphType();

		if (graphType == GraphType.List) {
			// Reads the ZIP; a .rdf entry throws from importRDFResource, otherwise the throw below signals not-implemented.
			extractRdfEntries(requestBody);
			throw new NotImplementedException("Resource ZIP import is not yet implemented");
		} else {
			throw new BadRequestException("Bad request: ZIP import supports only Entry graphType of List (given: " + graphType + ") with 'application/zip' format");
		}
	}

	/**
	 * Empties a List entry, or removes the whole tree below it when {@code recursive} is set.
	 */
	public void deleteChildren(Entry entry, boolean recursive) {
		ListImpl l = (ListImpl) entry.getResource();
		if (recursive) {
			l.removeTree();
		} else {
			l.setChildren(new Vector<>());
		}
	}

	/**
	 * Serializes the children of a List entry as a JSON array of entry ids. Sorting is applied only when the
	 * filter asks for it and the list has at most 500 children; children that no longer resolve are dropped from
	 * the sorted output.
	 */
	public String serializeChildrenIds(Entry entry, ListFilter listFilter) {
		JSONArray array = new JSONArray();
		org.entrystore.List l = (org.entrystore.List) entry.getResource();
		List<URI> uris = l.getChildren();
		Set<String> IDs = new HashSet<>();
		for (URI u : uris) {
			String id = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/') + 1);
			IDs.add(id);
		}

		if (listFilter.sort() != null && (IDs.size() < 501)) {
			List<Entry> childrenEntries = new ArrayList<>();
			for (String id : IDs) {
				Entry childEntry = entry.getContext().get(id);
				if (childEntry != null) {
					childrenEntries.add(childEntry);
				} else {
					log.warn("Child entry {} in context {} does not exist, but is referenced by a list.", id, entry.getContext().getURI());
				}
			}

			ResourceJsonSerializer.sortChildrenEntries(childrenEntries, ResourceJsonSerializer.ListParams.withoutPagination(listFilter));

			for (Entry childEntry : childrenEntries) {
				URI childURI = childEntry.getEntryURI();
				String id = (childURI.toASCIIString()).substring((childURI.toASCIIString()).lastIndexOf('/') + 1);
				array.put(id);
			}
		} else {
			if (IDs.size() > 500) {
				log.warn("No sorting performed because of list size bigger than 500 children");
			}
			for (String id : IDs) {
				array.put(id);
			}
		}
		return array.toString();
	}

	private void extractRdfEntries(byte[] requestBody) {
		File tmpFile = null;
		try {
			tmpFile = writeStreamToTmpFile(new ByteArrayInputStream(requestBody));
			importZipFile(tmpFile);
		} catch (IOException ioe) {
			throw new InternalServerErrorException("Failed to process ZIP import", ioe);
		} finally {
			if (tmpFile != null) {
				try {
					Files.deleteIfExists(tmpFile.toPath());
				} catch (IOException e) {
					log.warn("[IMPORT] Failed to delete temporary ZIP file: {}", tmpFile, e);
				}
			}
		}
	}

	private void importZipFile(File tmpFile) throws IOException {
		try (ZipFile zipFile = new ZipFile(tmpFile)) {
			Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
			while (zipEntries.hasMoreElements()) {
				ZipEntry entry = zipEntries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || !name.endsWith(".rdf")) {
					continue;
				}
				String fileString;
				try (InputStream fileIS = zipFile.getInputStream(entry)) {
					StringWriter writer = new StringWriter();
					IOUtils.copy(fileIS, writer, StandardCharsets.UTF_8);
					fileString = writer.toString();
				}
				importRDFResource(fileString);
			}
		}
	}

	private File writeStreamToTmpFile(InputStream is) throws IOException {
		File tmpFile = File.createTempFile("entrystore_res_import", ".zip", importTmpDir);
		log.info("[IMPORT] Created temporary file: {}", tmpFile);
		try (OutputStream fos = Files.newOutputStream(tmpFile.toPath())) {
			FileOperations.copyFile(is, fos);
		}
		return tmpFile;
	}

	private void importRDFResource(String rdfString) {
		throw new NotImplementedException("RDF resource import is not yet implemented");
	}
}
