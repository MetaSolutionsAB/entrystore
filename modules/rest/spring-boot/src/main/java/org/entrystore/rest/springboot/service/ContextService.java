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
import org.entrystore.config.Config;
import org.entrystore.impl.EntryNamesContext;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.BadRequestHtmlException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.GraphUtil;
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
	private final Config esConfig;


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

		} else if (context instanceof EntryNamesContext && entryName != null) {

			Entry matchedEntry = ((EntryNamesContext) context).getEntryByName(entryName);
			if (matchedEntry != null) {
				return List.of(matchedEntry.getId());
			} else {
				return Collections.emptyList();
			}
		}

		return context.getEntries()
				.stream()
				.map(uri -> {
					Entry entry = context.getByEntryURI(uri);
					if (entry == null) {
						log.warn("No entry found for this referenced URI: {}", uri);
						return null;
					}
					return entry.getId();
				})
				.filter(Objects::nonNull)
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
					throw new InternalServerErrorException("Exception copying the data to a temporary file for context import. Message: " + e.getMessage(), e);
				}

				repositoryManager.getContextManager().importContext(context.getEntry(), tmpFile);

			} else {
				throw new BadRequestException("Unable to import file, received null data");
			}
		} catch (IOException e) {
			throw new BadRequestHtmlException("Failed to import data", "Error importing data");
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
		try {
			Set<URI> users = new HashSet<>();

			// TODO: refactor to generate the exports in-memory only and return by the endpoint, instead of current:
			//  in-memory to disk, then disk to endpoint

			// create temp files
			File tmpExport = File.createTempFile(tmpPrefix, ".zip");
			tmpExport.deleteOnExit();
			File tmpTriples = File.createTempFile(tmpPrefix + "triples_", ".rdf");
			tmpTriples.deleteOnExit();
			File tmpProperties = File.createTempFile(tmpPrefix + "info_", ".properties");
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
			OutputStream fos = Files.newOutputStream(tmpProperties.toPath());
			exportProps.store(fos, "EntryStore export information");
			fos.close();

			// create zip stream
			ZipOutputStream zipOS = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpExport.toPath())));

			// add triples to zip file
			ZipEntry zeTriples = new ZipEntry("triples.rdf");
			zeTriples.setSize(tmpTriples.length());
			zeTriples.setTime(tmpTriples.lastModified());
			zeTriples.setMethod(ZipEntry.DEFLATED);
			zipOS.putNextEntry(zeTriples);

			int bytesRead;
			byte[] buffer = new byte[8192];

			InputStream is = new BufferedInputStream(Files.newInputStream(tmpTriples.toPath()), 8192);
			while ((bytesRead = is.read(buffer)) != -1) {
				zipOS.write(buffer, 0, bytesRead);
			}
			is.close();

			// add properties to zip file
			ZipEntry zeProperties = new ZipEntry("export.properties");
			zeProperties.setSize(tmpProperties.length());
			zeProperties.setTime(tmpProperties.lastModified());
			zeProperties.setMethod(ZipEntry.DEFLATED);
			zipOS.putNextEntry(zeProperties);

			is = Files.newInputStream(tmpProperties.toPath());
			while ((bytesRead = is.read(buffer)) != -1) {
				zipOS.write(buffer, 0, bytesRead);
			}
			is.close();

			// add resource files to zip file
			String contextPath = esConfig.getString(Settings.DATA_FOLDER);
			if (contextPath != null) {
				File contextPathFile = new File(contextPath);
				File contextFolder = new File(contextPathFile, contextId);
				File[] contextFiles = contextFolder.listFiles();
				if (contextFiles != null) {
					for (File contextFile : contextFiles) {
						ZipEntry zeResource = new ZipEntry("resources/" + contextFile.getName());
						zeResource.setMethod(ZipEntry.DEFLATED);
						zeResource.setSize(contextFile.length());
						zeResource.setTime(contextFile.lastModified());
						zipOS.putNextEntry(zeResource);
						is = new BufferedInputStream(Files.newInputStream(contextFile.toPath()), 8192);
						while ((bytesRead = is.read(buffer)) != -1) {
							zipOS.write(buffer, 0, bytesRead);
						}
					}
				} else {
					log.warn("The data path of context {} is not a folder: {}", contextId, contextFolder);
				}
			} else {
				log.error("No EntryStore data folder configured");
			}

			// some cleanup
			zipOS.flush();
			zipOS.close();
			tmpTriples.delete();
			tmpProperties.delete();

			// return the zip file
			return tmpExport;

		} catch (IOException | RepositoryException ex) {
			throw new InternalServerErrorException("Exception generating Context export for contextId '" + contextId
					+ "'. Message: " + ex.getMessage(), ex);
		}
	}

}
