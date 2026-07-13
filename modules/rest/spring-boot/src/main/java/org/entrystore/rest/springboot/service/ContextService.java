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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.trig.TriGWriter;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.EntryNamesContext;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextService {

	private final RepositoryManagerImpl repositoryManager;
	private final ReservedNamesService reservedNames;
	private final PrincipalManager principalManager;

	@Value("${entrystore.data.folder:#{null}}")
	private String dataFolder;


	/**
	 * Retrieves the {@link Context} associated with the given context ID or throws an exception if no such context exists.
	 *
	 * @param contextId the ID of the context to retrieve
	 * @return the context associated with the given ID
	 * @throws EntityNotFoundException if the context with the specified ID is not found
	 */
	public Context getContextOrThrow(String contextId) {
		Context context = getContext(contextId);
		if (context == null) {
			throw new EntityNotFoundException("Context with id '" + contextId + "' does not exist");
		}
		return context;
	}

	public List<String> getContextEntries(String contextId, boolean deletedEntries, String entryName) {

		Context context = getContextOrThrow(contextId);

		if (deletedEntries) {

			return context.getDeletedEntries().keySet()
					.stream()
					.map(URI::toString)
					.map(uri -> uri.substring(uri.lastIndexOf("/") + 1))
					.collect(Collectors.toList());

		} else if (context instanceof EntryNamesContext namesContext && entryName != null) {

			Entry matchedEntry = namesContext.getEntryByName(entryName);
			if (matchedEntry != null) {
				return List.of(matchedEntry.getId());
			} else {
				return Collections.emptyList();
			}
		}

		// D4: the entry id is the last segment of the entry URI (same derivation the deleted-entries
		// branch above uses), so we do not load every entry just to read its id.
		return context.getEntries()
				.stream()
				.map(URI::toString)
				.map(uri -> uri.substring(uri.lastIndexOf("/") + 1))
				.toList();
	}


	public Context getContext(String contextId) {

		ContextManager cm = repositoryManager.getContextManager();

		if (cm != null && contextId != null) {
			// Verify contextId against in-memory reservedNames on each context fetch, to minimise repository queries
			if (reservedNames.isReservedName(contextId.toLowerCase())) {
				log.error("Context ID is a reserved term and must not be used: \"{}\". This error is likely to be caused by an error in the REST routing.", contextId);
			} else {
				return cm.getContext(contextId);
			}
		}
		return null;
	}

	public File exportContextToAZipFile(Context context, boolean metadataOnly, String rdfFormat) {

		Class<? extends RDFWriter> writer = GraphUtil.getRDFWriterClassForMediaType(rdfFormat);
		if (writer == null) {
			if (rdfFormat != null) {
				log.warn("No RDF writer for requested export format '{}', falling back to TriG",
						HttpUtil.sanitizeForLog(rdfFormat));
			}
			writer = TriGWriter.class;
		}

		return getExport(context, metadataOnly, writer);
	}

	public void importContextDataFromFile(Context context, InputStream input) {
		File tmpFile = null;
		try {
			tmpFile = File.createTempFile("entrystore_context_import", ".zip");
			if (input != null) {
				try {
					FileOperations.copyFile(input, Files.newOutputStream(tmpFile.toPath()));
				} catch (IOException e) {
					throw new InternalServerErrorException("Exception copying the data to a temporary file for context import", e);
				}

				try {
					repositoryManager.getContextManager().importContext(context.getEntry(), tmpFile);
				} catch (org.entrystore.repository.RepositoryException e) {
					throw new BadRequestException("Failed to import context data from the uploaded file", e);
				}

			} else {
				throw new BadRequestException("Unable to import file, received null data");
			}
		} catch (IOException e) {
			throw new BadRequestException("Failed to import data", e);
		} finally {
			if (tmpFile != null) {
				tmpFile.delete();
			}
		}
	}

	private File getExport(Context context, boolean metadataOnly, Class<? extends RDFWriter> writer) {

		String contextId = context.getEntry().getId();
		ContextManager contextManager = repositoryManager.getContextManager();
		String tmpPrefix = "entrystore_context_" + contextId + "_export_";
		File tmpExport = null;
		File tmpTriples = null;
		File tmpProperties = null;
		boolean success = false;
		try {
			Set<URI> users = new HashSet<>();

			// TODO: refactor to generate the exports in-memory only and return by the endpoint, instead of current:
			//  in-memory to disk, then disk to endpoint

			// create temp files
			tmpExport = File.createTempFile(tmpPrefix, ".zip");
			tmpExport.deleteOnExit();
			tmpTriples = File.createTempFile(tmpPrefix + "triples_", ".rdf");
			tmpTriples.deleteOnExit();
			tmpProperties = File.createTempFile(tmpPrefix + "info_", ".properties");
			tmpProperties.deleteOnExit();

			// write context's triples to a rdf file
			log.info("Exporting triples of context {}", context.getURI());
			contextManager.exportContext(context.getEntry(), tmpTriples, users, metadataOnly, writer);

			// write export properties to a property file
			Properties exportProps = new Properties();
			exportProps.put("contextEntryURI", context.getEntry().getEntryURI().toString());
			exportProps.put("contextResourceURI", context.getEntry().getResourceURI().toString());
			exportProps.put("contextMetadataURI", context.getEntry().getLocalMetadataURI().toString());
			exportProps.put("contextRelationURI", context.getEntry().getRelationURI().toString());
			exportProps.put("baseURI", repositoryManager.getRepositoryURL().toString());
			exportProps.put("exportDate", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
			exportProps.put("exportingUser", principalManager.getAuthenticatedUserURI().toString());
			if (!users.isEmpty()) {
				StringBuilder userList = new StringBuilder();
				for (URI uri : users) {
					String uriStr = uri.toString();
					String userID = uriStr.substring(uriStr.lastIndexOf("/") + 1);
					userList.append(userID);
					User u = principalManager.getUser(uri);
					if (u != null) {
						String alias = u.getName();
						if (alias != null) {
							userList.append(":").append(alias);
						}
					}
					userList.append(",");
				}
				userList.deleteCharAt(userList.length() - 1);
				exportProps.put("containedUsers", userList.toString());
			}
			try (OutputStream fos = Files.newOutputStream(tmpProperties.toPath())) {
				exportProps.store(fos, "EntryStore export information");
			}

			// create zip stream
			try (ZipOutputStream zipOS = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpExport.toPath())))) {
				addZipEntry(zipOS, "triples.rdf", tmpTriples);
				addZipEntry(zipOS, "export.properties", tmpProperties);

				// add resource files to zip file
				if (dataFolder != null) {
					File contextPathFile = new File(dataFolder);
					File contextFolder = new File(contextPathFile, contextId);
					File[] contextFiles = contextFolder.listFiles();
					if (contextFiles != null) {
						for (File contextFile : contextFiles) {
							addZipEntry(zipOS, "resources/" + contextFile.getName(), contextFile);
						}
					} else {
						log.warn("The data path of context {} is not a folder: {}", contextId, contextFolder);
					}
				} else {
					log.error("No EntryStore data folder configured");
				}
			}

			// return the zip file
			success = true;
			return tmpExport;

		} catch (IOException | RepositoryException ex) {
			throw new InternalServerErrorException("Exception generating context export for contextId '" + contextId + "'", ex);
		} finally {
			if (tmpTriples != null) {
				tmpTriples.delete();
			}
			if (tmpProperties != null) {
				tmpProperties.delete();
			}
			if (!success && tmpExport != null) {
				tmpExport.delete();
			}
		}
	}

	/**
	 * Writes {@code source} into the zip stream as a DEFLATED entry named {@code name}, carrying the
	 * file's modification time. Sizes and CRC are computed by the stream and written in the entry's
	 * data descriptor.
	 */
	static void addZipEntry(ZipOutputStream zipOS, String name, File source) throws IOException {
		ZipEntry zipEntry = new ZipEntry(name);
		zipEntry.setTime(source.lastModified());
		zipEntry.setMethod(ZipEntry.DEFLATED);
		zipOS.putNextEntry(zipEntry);
		try (InputStream is = new BufferedInputStream(Files.newInputStream(source.toPath()), 8192)) {
			is.transferTo(zipOS);
		}
	}

}
