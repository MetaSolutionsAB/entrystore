/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

package org.entrystore.rest.util;

import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.FileCleanerCleanup;
import org.entrystore.Entry;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.data.Method;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.util.Date;
import java.util.HashMap;


/**
 * A Util class for the REST module
 * 
 * @author Hannes Ebner
 */
public class Util {
	
	static Logger log = LoggerFactory.getLogger(Util.class);

	/**
	 * Takes the parameters from the URL and puts them into a map instead.
	 * 
	 * @param request
	 *            the request which contains the parameters.
	 * @return A map with the parameters.
	 */
	public static HashMap<String, String> parseRequest(String request) {
		HashMap<String, String> argsAndVal = new HashMap<String, String>();

		int r = request.lastIndexOf("?");
		String req = request.substring(r + 1);
		String[] arguments = req.split("&");

		try {
			for (int i = 0; i < arguments.length; i++) {
				if (arguments[i].contains("=")) {
					String[] elements = arguments[i].split("=");
					argsAndVal.put(elements[0], elements[1]);
				} else {
					argsAndVal.put(arguments[i], "");
				}
			}
		} catch (IndexOutOfBoundsException e) {
			// special case!
			argsAndVal.put(req, "");
		}
		return argsAndVal;
	}

	/**
	 * We support If-Unmodified-Since (this is a dirty hack due some
	 * shortcomings of the current Restlet version). Restlets seem to evaluate
	 * the conditions even before the representation methods are called, so our
	 * only way on reacting on HTTP conditions is here in the constructor by
	 * setting the affected date to null if the request is ok.
	 * 
	 * THIS IS ONLY A HACK AND SHOULD BE REPLACED WITH PROPER HANDLING DIRECTLY
	 * IN RESTLETS WHEN SUPPORTED.
	 */
	public static void handleIfUnmodifiedSince(Entry entry, Request request) {
		if (entry != null && request != null) {
			Date modDate = entry.getModifiedDate();
			Date unmodSince = request.getConditions().getUnmodifiedSince();
			if (modDate != null && unmodSince != null) {
				// We can't do a simple equals because the objects differ
				// slightly because the HTTP date supplied in the
				// If-Unmodified-Since parameter does not contain information
				// about milliseconds
				long dT = modDate.getTime() - unmodSince.getTime();
				if (dT < 0) {
					dT *= -1;
				}
				if (dT < 1000 || Method.GET.equals(request.getMethod())) {
					request.getConditions().setUnmodifiedSince(null);
				}
			} else if (modDate == null || unmodSince == null) {
				request.getConditions().setUnmodifiedSince(null);
			}
		}
	}
	
	public static JSONObject createResponseObject(int statusCode, String message) {
		JSONObject obj = new JSONObject();
		try {
			obj.put("status", statusCode);
			obj.put("message", message);
		} catch (JSONException e) {
			log.error(e.getMessage());
		}
		return obj;
	}

	public static RestletFileUpload createRestletFileUpload(Context context) {
		if (context == null) {
			throw new IllegalArgumentException("Context must not be null");
		}
		// Content with size above 100kB is cached on disk
		DiskFileItemFactory dfif = new DiskFileItemFactory(1024*100, null);
		RestletFileUpload upload = new RestletFileUpload(dfif);
		ServletContext sc = (ServletContext) context.getServerDispatcher().getContext().getAttributes().get("org.restlet.ext.servlet.ServletContext");
		if (sc != null) {
			log.debug("Setting FileCleaningTracker on DiskFileItemFactory");
			dfif.setFileCleaningTracker(FileCleanerCleanup.getFileCleaningTracker(sc));
		} else {
			log.debug("Unable to get ServletContext instance, no FileCleaningTracker assigned to DiskFileItemFactory");
		}
		return upload;
	}

}