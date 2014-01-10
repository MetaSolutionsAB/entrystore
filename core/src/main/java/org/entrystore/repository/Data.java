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

package org.entrystore.repository;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Data resources contains digital information, e.g. a file, that is managed
 * within the repository. For a resource to be a data resource the
 * {@link EntryType} must be {@link EntryType#Local} and the
 * {@link ResourceType} must be {@link ResourceType#None}.
 * 
 * @author matthias
 */
public interface Data extends Resource {

	/**
	 * The stream must contain EOF in the end
	 * 
	 * @param is
	 *            a stream containing the data, it will be saved in the
	 *            repository.
	 * @return true if the representation was successfully stored.
	 * @throws IOException 
	 */
	void setData(InputStream is) throws QuotaException, IOException;

	/**
	 * @return an InputStream or null if there are no data yet.
	 */
	OutputStream getData();

	/**
	 * @return a File or null if there are no data yet.
	 */
	File getDataFile();

	/**
	 * Deletes a file if it exists
	 */
	boolean delete();

}