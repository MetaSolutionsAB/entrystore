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

package org.entrystore.repository.backup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class copies a directory. 
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se) 
 *
 */
public class CopyDirectory{
	
	private static Logger log = LoggerFactory.getLogger(CopyDirectory.class);
	
	public static void main(String[] args) throws IOException{

		CopyDirectory cd = new CopyDirectory();
		BufferedReader in = new BufferedReader
		(new InputStreamReader(System.in));
		log.info("Enter the source directory or file name : ");

		String source = in.readLine();

		File src = new File(source);

		log.info("Enter the destination directory or file name : ");
		String destination = in.readLine();

		File dst = new File(destination); 

		cd.copyDirectory(src, dst);

	}

	/**
	 * 
	 * @param srcPath	the path that should be copied
	 * @param dstPath	the path that will saves the copies
	 * @throws IOException
	 */
	static public void copyDirectory(File srcPath, File dstPath) throws IOException {
		if (srcPath.isDirectory()) {
			if (!dstPath.exists()) {
				dstPath.mkdir();
			}
			
			String files[] = srcPath.list();
			for(int i = 0; i < files.length; i++) {
				copyDirectory(new File(srcPath, files[i]), 
						new File(dstPath, files[i]));
			}
		} else {
			if(!srcPath.exists()) {
				log.error(srcPath + ": file or directory does not exist.");				
			} else {
				InputStream in = new FileInputStream(srcPath);
				OutputStream out = new FileOutputStream(dstPath); 
				// Transfer bytes from in to out
				byte[] buf = new byte[1024];
				int len;

				while ((len = in.read(buf)) > 0) {
					out.write(buf, 0, len);
				}
				in.close();
				out.close();
			}
		}
		log.debug("Directory copied.");
	}
}