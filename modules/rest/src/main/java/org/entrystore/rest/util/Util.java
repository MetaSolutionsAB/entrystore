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

package org.entrystore.rest.util;

import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.FileCleanerCleanup;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Entry;
import org.entrystore.rest.EntryStoreApplication;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.data.Tag;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.net.URLDecoder;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;


/**
 * A Util class for the REST module
 *
 * @author Hannes Ebner
 */
public class Util {

	static Logger log = LoggerFactory.getLogger(Util.class);

	public static Set<String> dangerousFileExtensions = new HashSet<>();

	static {
		dangerousFileExtensions.add("apk");
		dangerousFileExtensions.add("app");
		dangerousFileExtensions.add("asp");
		dangerousFileExtensions.add("aspx");
		dangerousFileExtensions.add("bat");
		dangerousFileExtensions.add("bin");
		dangerousFileExtensions.add("cab");
		dangerousFileExtensions.add("cmd");
		dangerousFileExtensions.add("com");
		dangerousFileExtensions.add("command");
		dangerousFileExtensions.add("cpl");
		dangerousFileExtensions.add("csh");
		dangerousFileExtensions.add("ex");
		dangerousFileExtensions.add("exe");
		dangerousFileExtensions.add("gadget");
		dangerousFileExtensions.add("inf");
		dangerousFileExtensions.add("ins");
		dangerousFileExtensions.add("inx");
		dangerousFileExtensions.add("ipa");
		dangerousFileExtensions.add("isu");
		dangerousFileExtensions.add("js");
		dangerousFileExtensions.add("jse");
		dangerousFileExtensions.add("jsp");
		dangerousFileExtensions.add("jsx");
		dangerousFileExtensions.add("ksh");
		dangerousFileExtensions.add("lnk");
		dangerousFileExtensions.add("msc");
		dangerousFileExtensions.add("msi");
		dangerousFileExtensions.add("msp");
		dangerousFileExtensions.add("mst");
		dangerousFileExtensions.add("osx");
		dangerousFileExtensions.add("out");
		dangerousFileExtensions.add("paf");
		dangerousFileExtensions.add("pif");
		dangerousFileExtensions.add("php");
		dangerousFileExtensions.add("pl");
		dangerousFileExtensions.add("plx");
		dangerousFileExtensions.add("prg");
		dangerousFileExtensions.add("ps1");
		dangerousFileExtensions.add("rb");
		dangerousFileExtensions.add("reg");
		dangerousFileExtensions.add("rgs");
		dangerousFileExtensions.add("run");
		dangerousFileExtensions.add("scr");
		dangerousFileExtensions.add("sct");
		dangerousFileExtensions.add("shb");
		dangerousFileExtensions.add("shs");
		dangerousFileExtensions.add("u3p");
		dangerousFileExtensions.add("vb");
		dangerousFileExtensions.add("vbe");
		dangerousFileExtensions.add("vbs");
		dangerousFileExtensions.add("vbscript");
		dangerousFileExtensions.add("workflow");
		dangerousFileExtensions.add("ws");
		dangerousFileExtensions.add("wsf");
		dangerousFileExtensions.add("wsh");
	}

	/**
	 * Takes the parameters from the URL and puts them into a map instead.
	 *
	 * @param request
	 *            the request which contains the parameters.
	 * @return A map with the parameters.
	 */
	public static HashMap<String, String> parseRequest(String request) {
		HashMap<String, String> argsAndVal = new HashMap<>();

		int r = request.lastIndexOf("?");
		String req = request.substring(r + 1);
		String[] arguments = StringUtils.split(req, '&');

		try {
			for (String argument : arguments) {
				String[] elements = StringUtils.split(argument, '=');
				// URLDecoder is for application/x-www-form-urlencoded decoding, which is not exact with URL params decoding
				// (includes '+' to 'space' replacement), hence need to replace '+' with '%2B'
				argsAndVal.put(elements[0], elements.length == 1 ? "" : URLDecoder.decode(elements[1].replace("+", "%2B"), UTF_8));
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
				if (dT < 1000) {
					request.getConditions().setUnmodifiedSince(null);
				}
			} else {
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
		ServletContext sc = EntryStoreApplication.getServletContext(context);
		if (sc != null) {
			log.debug("Setting FileCleaningTracker on DiskFileItemFactory");
			dfif.setFileCleaningTracker(FileCleanerCleanup.getFileCleaningTracker(sc));
		} else {
			log.debug("Unable to get ServletContext instance, no FileCleaningTracker assigned to DiskFileItemFactory");
		}
		return upload;
	}

	public static Tag createTag(Date date) {
		return new Tag(Long.toString(date.getTime()), false);
	}

	public static String sanitizeFilename(String filename) {
		String fileExt = FilenameUtils.getExtension(filename);
		if (fileExt != null && dangerousFileExtensions.contains(fileExt.toLowerCase())) {
			return filename + "_dangerous";
		}
		return filename;
	}

}
