/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.resources;

import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.n3.N3Writer;
import org.eclipse.rdf4j.rio.ntriples.NTriplesWriter;
import org.eclipse.rdf4j.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.eclipse.rdf4j.rio.trig.TriGWriter;
import org.eclipse.rdf4j.rio.trix.TriXWriter;
import org.eclipse.rdf4j.rio.turtle.TurtleWriter;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.util.JSONErrorMessages;
import org.jetbrains.annotations.NotNull;
import org.restlet.data.Disposition;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.FileRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/**
 * This class supports the export of single contexts.
 *
 * @author Hannes Ebner
 */
public class ExportResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ExportResource.class);

	@Get
	public Representation represent() {
		try {
			if (context == null) {
				log.error("Unable to find context with that ID");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}

			boolean metadataOnly = parameters.containsKey("metadataOnly");

			String format = null;
			if (parameters.containsKey("rdfFormat")) {
				format = parameters.get("rdfFormat");
			}

			Class<? extends RDFWriter> writer = determineWriter(format);
			return getExport(metadataOnly, writer);
		} catch (AuthorizationException e) {
			log.error("unauthorizedGET");
			return unauthorizedGET();
		}
	}

	private static @NotNull Class<? extends RDFWriter> determineWriter(String format) {
		Class<? extends RDFWriter> writer;
		if (format == null || RDFFormat.TRIG.getDefaultMIMEType().equals(format)) {
			writer = TriGWriter.class;
		} else if (RDFFormat.RDFXML.getDefaultMIMEType().equals(format)) {
			writer = RDFXMLPrettyWriter.class;
		} else if (RDFFormat.N3.getDefaultMIMEType().equals(format)) {
			writer = N3Writer.class;
		} else if (RDFFormat.TURTLE.getDefaultMIMEType().equals(format)) {
			writer = TurtleWriter.class;
		} else if (RDFFormat.TRIX.getDefaultMIMEType().equals(format)) {
			writer = TriXWriter.class;
		} else if (RDFFormat.NTRIPLES.getDefaultMIMEType().equals(format)) {
			writer = NTriplesWriter.class;
		} else {
			writer = TriGWriter.class;
		}
		return writer;
	}

	private Representation getExport(boolean metadataOnly, Class<? extends RDFWriter> writer) throws AuthorizationException {
		Representation result = null;
		String tmpPrefix = "entrystore_context_" + contextId + "_export_";
		try {
			Set<URI> users = new HashSet<>();

			// create temp files
			File tmpExport = File.createTempFile(tmpPrefix, ".zip");
			tmpExport.deleteOnExit();
			File tmpTriples = File.createTempFile(tmpPrefix + "triples_", ".rdf");
			tmpTriples.deleteOnExit();
			File tmpProperties = File.createTempFile(tmpPrefix + "info_", ".properties");
			tmpProperties.deleteOnExit();

			// write context's triples to a rdf file
			log.info("Exporting triples of context {}", context.getURI());
			getCM().exportContext(context.getEntry(), tmpTriples, users, metadataOnly, writer);

			// write export properties to a property file
			Properties exportProps = new Properties();
			exportProps.put("contextEntryURI", context.getEntry().getEntryURI().toString());
			exportProps.put("contextResourceURI", context.getEntry().getResourceURI().toString());
			exportProps.put("contextMetadataURI", context.getEntry().getLocalMetadataURI().toString());
			exportProps.put("contextRelationURI", context.getEntry().getRelationURI().toString());
			exportProps.put("baseURI", getRM().getRepositoryURL().toString());
			exportProps.put("exportDate", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
			exportProps.put("exportingUser", getPM().getAuthenticatedUserURI().toString());
			if (!users.isEmpty()) {
				StringBuffer userList = new StringBuffer();
				for (URI uri : users) {
					String uriStr = uri.toString();
					String userID = uriStr.substring(uriStr.lastIndexOf("/") + 1);
					userList.append(userID);
					User u = getPM().getUser(uri);
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
			String contextPath = getRM().getConfiguration().getString(Settings.DATA_FOLDER);
			if (contextPath != null) {
				File contextPathFile = new File(contextPath);
				File contextFolder = new File(contextPathFile, contextId);
				File[] contextFiles = contextFolder.listFiles();
				if (contextFiles != null) {
					for (int i = 0; i < contextFiles.length; i++) {
						ZipEntry zeResource = new ZipEntry("resources/" + contextFiles[i].getName());
						zeResource.setMethod(ZipEntry.DEFLATED);
						zeResource.setSize(contextFiles[i].length());
						zeResource.setTime(contextFiles[i].lastModified());
						zipOS.putNextEntry(zeResource);
						is = new BufferedInputStream(Files.newInputStream(contextFiles[i].toPath()), 8192);
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
			result = new ExportFileRepresentation(tmpExport, MediaType.APPLICATION_ZIP);
			result.getDisposition().setType(Disposition.TYPE_ATTACHMENT);
			result.getDisposition().setFilename("context_" + contextId + "_export.zip");
			result.setSize(tmpExport.length());
		} catch (IOException | RepositoryException ex) {
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, ex.getMessage());
			log.error("Exception in getExport(): {}", ex.getMessage(), ex);
		}

		return result;
	}

	private class ExportFileRepresentation extends FileRepresentation {

		public ExportFileRepresentation(File file, MediaType mediaType) {
			super(file, mediaType);
		}

		@Override
		public void release() {
			File file = getFile();
			if (file.exists() && file.isFile() && file.canWrite()) {
				log.info("Removing temporary export file: {}", file);
				file.delete();
			}
			super.release();
		}

	}

}
