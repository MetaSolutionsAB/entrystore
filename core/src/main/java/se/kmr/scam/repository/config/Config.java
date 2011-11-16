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

package se.kmr.scam.repository.config;

import java.awt.Color;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

/**
 * Methods to handle a configuration. Basically a wrapping interface around
 * already existing configuration solutions. The main purpose is to make format
 * independent implementations possible, and to be able to switch the
 * configuration backend at a later point.
 * 
 * To get a synchronized view of a Config object, the static method
 * Configurations.synchronizedConfig can be called (similar to the Collections
 * framework).
 * 
 * <p>
 * Property = Key + Value(s)<br>
 * Key = Distinct name of a configuration setting<br>
 * <p>
 * A key may contain dots to indicate a hierarchical separation.
 * 
 * @author Hannes Ebner
 * @version $Id$
 * @see Configurations
 */
public interface Config {

	/* Global operations */

	/**
	 * Clears the whole configuration.
	 */
	void clear();

	/**
	 * @return True if the configuration is empty.
	 */
	boolean isEmpty();

	/**
	 * @return True if the configuration has been modified since the last time
	 *         it was saved.
	 */
	boolean isModified();

	/**
	 * Saves the configuration at a given location.
	 * 
	 * @param configURL
	 *            URL of the location. Right now only local locations are
	 *            supported.
	 * @throws ConfigurationException
	 */
	void save(URL configURL) throws IOException;

	/**
	 * Loads a configuration from a given location.
	 * 
	 * @param configURL
	 *            URL of the location. Right now only local locations are
	 *            supported.
	 * @throws ConfigurationException
	 */
	void load(URL configURL) throws IOException;
	
	/* Property Change Listeners */
	
	/**
	 * Adds a PropertyChangeListener to the configuration.
	 * 
	 * @param listener PropertyChangeListener.
	 */
	void addPropertyChangeListener(PropertyChangeListener listener);
	
	/**
	 * Adds a PropertyChangeListener to the configuration.
	 * 
	 * @param key Property key.
	 * @param listener PropertyChangeListener.
	 */
	void addPropertyChangeListener(String key, PropertyChangeListener listener);
	
	/**
	 * Adds a PropertyChangeListener from the configuration.
	 * 
	 * @param listener PropertyChangeListener.
	 */
	void removePropertyChangeListener(PropertyChangeListener listener);
	
	/**
	 * Adds a PropertyChangeListener from the configuration.
	 * 
	 * @param key Property key.
	 * @param listener PropertyChangeListener.
	 */
	void removePropertyChangeListener(String key, PropertyChangeListener listener);

	/* Properties */

	/**
	 * Clears all values of a given key.
	 * 
	 * @param key
	 *            Property key.
	 */
	void clearProperty(String key);

	/**
	 * Adds a property (key/value mapping) to the configuration. If the property
	 * already exists an additional value is added.
	 * 
	 * @param key
	 *            Property key.
	 * @param value
	 *            Value as Object. E.g. an int is added as new Integer(int).
	 */
	void addProperty(String key, Object value);

	/**
	 * Adds a List of values to a property. See also addProperty(String,
	 * Object).
	 * 
	 * @param key
	 *            Property key.
	 * @param values
	 *            List of objects.
	 */
	void addProperties(String key, List values);
	
	/**
	 * Adds a List of values to a property. See also addProperty(String,
	 * Object).
	 * 
	 * @param key
	 *            Property key.
	 * @param values
	 *            Iterator (e.g. from a List).
	 */
	void addProperties(String key, Iterator values);

	/**
	 * Sets the value of a property. Already existing values are overwritten.
	 * 
	 * @param key
	 *            Property key.
	 * @param value
	 *            Value as Object. E.g. an int is set as new Integer(int).
	 */
	void setProperty(String key, Object value);

	/**
	 * Sets a List of values of a property. See also setProperty(String,
	 * Object).
	 * 
	 * @param key
	 *            Property key.
	 * @param values
	 *            List of objects.
	 */
	void setProperties(String key, List values);
	
	/**
	 * Sets a List of values of a property. See also setProperty(String,
	 * Object).
	 * 
	 * @param key
	 *            Property key.
	 * @param values
	 *            Iterator (e.g. from a List).
	 */
	void setProperties(String key, Iterator values);

	/* Keys */

	/**
	 * Checks whether the configuration contains a given key.
	 * 
	 * @param key
	 *            Property key.
	 * @return True if the configuration contains the given key.
	 */
	boolean containsKey(String key);

	/**
	 * Returns a list of all set configuration keys.
	 * 
	 * @return List of keys as string values.
	 */
	List getKeyList();

	/**
	 * Returns a list of all set configuration keys under a given key.
	 * 
	 * @param prefix
	 *            Key prefix to set the point to start from in the configuration
	 *            tree.
	 * @return List of keys as string values.
	 */
	List getKeyList(String prefix);

	/* Get Values */

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as String, or null if the property is not found.
	 */
	String getString(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as String.
	 */
	String getString(String key, String defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns property values as a List of strings.
	 */
	List getStringList(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValues
	 *            Default values if the given property does not exist.
	 * @return Returns a property value as a List of strings.
	 */
	List getStringList(String key, List defaultValues);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as boolean.
	 */
	boolean getBoolean(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as boolean.
	 */
	boolean getBoolean(String key, boolean defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as byte.
	 */
	byte getByte(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as byte.
	 */
	byte getByte(String key, byte defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as double.
	 */
	double getDouble(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as double.
	 */
	double getDouble(String key, double defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as float.
	 */
	float getFloat(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as float.
	 */
	float getFloat(String key, float defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as int.
	 */
	int getInt(String key) throws NumberFormatException;

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as int.
	 */
	int getInt(String key, int defaultValue) throws NumberFormatException;

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as long.
	 */
	long getLong(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as long.
	 */
	long getLong(String key, long defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as short.
	 */
	short getShort(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as short.
	 */
	short getShort(String key, short defaultValue);

	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as URI.
	 */
	URI getURI(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as URI.
	 */
	URI getURI(String key, URI defaultValue);
	
	/**
	 * @param key
	 *            Property key.
	 * @return Returns a property value as Color.
	 */
	Color getColor(String key);

	/**
	 * @param key
	 *            Property key.
	 * @param defaultValue
	 *            Default value if the given property does not exist.
	 * @return Returns a property value as Color.
	 */
	Color getColor(String key, Color defaultValue);

}