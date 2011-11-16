/**
 * Copyright (c) 2007-2010
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

package se.kmr.scam.rest.resources;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXWriter;
import org.openrdf.rio.turtle.TurtleWriter;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.FileRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.jdil.JDILErrorMessages;
import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.User;
import se.kmr.scam.repository.PrincipalManager.AccessProperty;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.rest.ScamApplication;
import se.kmr.scam.rest.util.Util;

/**
 * This class supports the export of single contexts. 
 * 
 * @author Hannes Ebner
 */
public class ExportResource extends BaseResource {

	Logger log = LoggerFactory.getLogger(ExportResource.class);

	/** The contexts ID. */
	String contextId = null;

	/** The context object for the context */
	se.kmr.scam.repository.Context context = null;

	/** Parameters from the URL. Example: ?scam=umu&shame=kth */
	HashMap<String,String> parameters = null;
	
	private RepositoryManagerImpl rm;
	
	private String format;

	public ExportResource(Context context, Request request, Response response) {
		super(context, request, response);

		this.contextId =(String) getRequest().getAttributes().get("context-id"); 
	
		String remainingPart = request.getResourceRef().getRemainingPart(); 

		parameters = Util.parseRequest(remainingPart); 

		getVariants().add(new Variant(MediaType.APPLICATION_ZIP));
		getVariants().add(new Variant(MediaType.ALL));

		if(getCM() != null) {
			try {
				this.context = getCM().getContext(contextId);  
			} catch (NullPointerException e) {
				// not a context
				this.context = null; 
			}
		}
		
		if (parameters.containsKey("rdfFormat")) {
			this.format = parameters.get("rdfFormat");
		}
		
		rm = ((ScamApplication) context.getAttributes().get(ScamApplication.KEY)).getRM();
	}

	public Representation represent(Variant variant) {
		try {
			if (context == null) {
				log.error("Unable to find context with that ID"); 
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND); 
				return new JsonRepresentation(JDILErrorMessages.errorCantNotFindEntry); 
			}
			
			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}

			boolean metadataOnly = false;
			if (parameters.containsKey("metadataOnly")) {
				metadataOnly = true;
			}
			
			Class<? extends RDFWriter> writer = null;
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
			
			return getExport(metadataOnly, writer);
		} catch(AuthorizationException e) {
			log.error("unauthorizedGET");
			return unauthorizedGET();
		}
	}
		
	private Representation getExport(boolean metadataOnly,  Class<? extends RDFWriter> writer) throws AuthorizationException {
		Representation result = null;
		String tmpPrefix = "scam_context_" + contextId + "_export_";
		try {
			Set<URI> users = new HashSet<URI>();
			
			// create temp files
			File tmpExport = File.createTempFile(tmpPrefix, ".zip");
			tmpExport.deleteOnExit();
			File tmpTriples = File.createTempFile(tmpPrefix + "triples_", ".rdf");
			tmpTriples.deleteOnExit();
			File tmpProperties = File.createTempFile(tmpPrefix + "info_", ".properties");
			tmpProperties.deleteOnExit();
			
			// write context's triples to an rdf file
			log.info("Exporting triples of context " + context.getURI());
			getCM().exportContext(context.getEntry(), tmpTriples, users, metadataOnly, writer);
			
			// write export properties to a property file
			Properties exportProps = new Properties();
			exportProps.put("contextEntryURI", context.getEntry().getEntryURI().toString());
			exportProps.put("contextResourceURI", context.getEntry().getResourceURI().toString());
			exportProps.put("contextMetadataURI", context.getEntry().getLocalMetadataURI().toString());
			exportProps.put("contextRelationURI", context.getEntry().getRelationURI().toString());
			exportProps.put("scamBaseURI", rm.getRepositoryURL().toString());
			exportProps.put("exportDate", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
			exportProps.put("exportingUser", rm.getPrincipalManager().getAuthenticatedUserURI().toString());
			if (!users.isEmpty()) {
				StringBuffer userList = new StringBuffer();
				for (URI uri : users) {
					String uriStr = uri.toString();
					String userID = uriStr.substring(uriStr.lastIndexOf("/") + 1);
					userList.append(userID);
					User u = rm.getPrincipalManager().getUser(uri);
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
			FileOutputStream fos = new FileOutputStream(tmpProperties);
			exportProps.store(fos, "SCAM export information");
			fos.close();
			
			// create zip stream			
			ZipOutputStream zipOS = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpExport)));
			
			// add triples to zip file
			ZipEntry zeTriples = new ZipEntry("triples.rdf");
			zeTriples.setSize(tmpTriples.length());
			zeTriples.setTime(tmpTriples.lastModified());
			zeTriples.setMethod(ZipEntry.DEFLATED);
			zipOS.putNextEntry(zeTriples);
			
			int bytesRead;
			byte[] buffer = new byte[8192];
			
			InputStream is = new BufferedInputStream(new FileInputStream(tmpTriples), 8192);
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
            
            bytesRead = 0;
            is = new FileInputStream(tmpProperties);
            while ((bytesRead = is.read(buffer)) != -1) {
            	zipOS.write(buffer, 0, bytesRead);
            }
            is.close();
            
            // add resource files to zip file
            String contextPath = rm.getConfiguration().getString(Settings.SCAM_DATA_FOLDER);
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
            			is = new BufferedInputStream(new FileInputStream(contextFiles[i]), 8192);
            			while ((bytesRead = is.read(buffer)) != -1) {
            				zipOS.write(buffer, 0, bytesRead);
            			}
            		}
            	} else {
            		log.warn("The data path of context " + contextId + " is not a folder: " + contextFolder);
            	}
            } else {
            	log.error("No SCAM data folder configured");
            }
			
			// some cleanup
			zipOS.flush();
			zipOS.close();
			tmpTriples.delete();
			tmpProperties.delete();
			
			// return the zip file
			result = new ExportFileRepresentation(tmpExport, MediaType.APPLICATION_ZIP);
			result.setDownloadable(true);
			result.setDownloadName("context_" + contextId + "_export.zip");
			result.setSize(tmpExport.length());
		} catch (IOException ioe) {
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, ioe.getMessage());
			log.error(ioe.getMessage(), ioe);
		} catch (RepositoryException re) {
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, re.getMessage());
			log.error(re.getMessage(), re);
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
				log.info("Removing temporary export file: " + file);
				file.delete();
			}
			super.release();
		}
		
	}

}