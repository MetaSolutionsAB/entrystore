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

package org.entrystore.repository.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.config.Config;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

/**
 * Wrapper around Java's Properties.
 * Some methods have been simplified, others just wrapped.<br>
 * 
 * <p>
 * See the static methods of the class Configurations for wrappers around the
 * Config interface, e.g. to get synchronized view of the object.
 * 
 * <p>
 * If a key maps only to one value, it is done the standard way:<br>
 * <pre>key=value</pre>
 * 
 * <p>
 * If a key maps to multiple values, the key is numbered:<br>
 * <pre>key.1=value1</pre>
 * <pre>key.2=value2</pre>
 * 
 * @author Hannes Ebner
 * @version $Id$
 * @see org.entrystore.config.Configurations
 * @see org.entrystore.config.Config
 */
public class PropertiesConfiguration implements Config {
	
	Log log = LogFactory.getLog(PropertiesConfiguration.class);

	/**
	 * The main resource in this object. Contains the configuration.
	 */
	private SortedProperties config;
	
	private PropertyChangeSupport pcs;
	
	private String configName;

	private boolean modified = false;

	/* Constructors */

	/**
	 * Initializes the object with an empty Configuration.
	 * 
	 * @param configName
	 *            Name of the configuration (appears as comment in the
	 *            configuration file).
	 */
	public PropertiesConfiguration(String configName) {
		this.configName = configName;
		config = new SortedProperties();
		pcs = new PropertyChangeSupport(this);
	}

	/* Generic helpers */

	/**
	 * Sets the modified status of this configuration.
	 * 
	 * @param modified
	 *            Status.
	 */
	private void setModified(boolean modified) {
		this.modified = modified;
	}
	
	private void checkFirePropertyChange(String key, Object oldValue, Object newValue) {
		if ((oldValue == null) && (newValue != null)) {
			pcs.firePropertyChange(key, oldValue, newValue);
		} else if ((oldValue != null) && (!oldValue.equals(newValue))) {
			pcs.firePropertyChange(key, oldValue, newValue);
		}
	}
	
	/* 
	 * List helpers
	 */
	
	private String numberedKey(String key, int number) {
		return key + "." + number;
	}
	
	private int getPropertyValueCount(String key) {
		int valueCount = 0;
		if (config.containsKey(key)) {
			valueCount = 1;
		} else {
			while (config.containsKey(numberedKey(key, valueCount + 1))) {
				valueCount++;
			}
		}
		return valueCount;
	}
	
	private synchronized void addPropertyValue(String key, Object value) {
		int valueCount = getPropertyValueCount(key);
		if ((valueCount == 1) && config.containsKey(key)) {
			String oldValue = config.getProperty(key);
			config.remove(key);
			config.setProperty(numberedKey(key, 1), oldValue);
			config.setProperty(numberedKey(key, 2), value.toString());
		} else if (valueCount > 1){
			config.setProperty(numberedKey(key, valueCount + 1), value.toString());
		} else if (valueCount == 0) {
			config.setProperty(key, value.toString());
		}
	}
	
	private void addPropertyValues(String key, List values) {
		addPropertyValues(key, values.iterator());
	}
	
	private synchronized void addPropertyValues(String key, Iterator it) {
		while (it.hasNext()) {
			addPropertyValue(key, it.next());
		}
	}
	
	private synchronized List<String> getPropertyValues(String key) {
		int valueCount = getPropertyValueCount(key);
		List<String> result = new ArrayList<>();
		if (valueCount == 1) {
			String value = config.getProperty(key);
			if (value == null) {
				value = config.getProperty(numberedKey(key, 1));
			}
			if (value != null) {
				result.add(value);
			}
		} else {
			for (int i = 1; i <= valueCount; i++) {
				result.add(config.getProperty(numberedKey(key, i)));
			}
		}
		return result;
	}
	
	private synchronized void clearPropertyValues(String key) {
		int valueCount = getPropertyValueCount(key);
		if (valueCount > 1) {
			for (int i = 1; i <= valueCount; i++) {
				config.remove(numberedKey(key, i));
			}
		}
		config.remove(key);
	}
	
	private void setPropertyValues(String key, List values) {
		setPropertyValues(key, values.iterator());
	}
	
	private synchronized void setPropertyValues(String key, Iterator it) {
		clearPropertyValues(key);
		addPropertyValues(key, it);
	}
	
	/*
	 * Interface implementation
	 */

	/* Generic */

	public void clear() {
		config.clear();
		setModified(true);
	}

	public boolean isEmpty() {
		return config.isEmpty();
	}

	public boolean isModified() {
		return modified;
	}

	public void load(URL configURL) throws IOException {
		InputStream is = null;
		try {
			URL escapedURL = new URL(configURL.toString().replaceAll(" ", "%20"));
			is = escapedURL.openStream();
			config.load(is);
		} finally {
			if (is != null) {
				is.close();
			}
		}
	}

	public void save(URL configURL) throws IOException {
		try {
			String escapedURL = configURL.toString().replaceAll(" ", "%20");
			URI url = new URI(escapedURL.toString());
			File file = new File(url);
			OutputStreamWriter output = new OutputStreamWriter(Files.newOutputStream(file.toPath()), "UTF-8");
			config.store(output, configName);
			output.close();
		} catch (URISyntaxException e) {
			throw new IOException(e.getMessage());
		}
		setModified(false);
	}
	
	/* Property Change Listeners */
	
	public void addPropertyChangeListener(PropertyChangeListener listener) {
		pcs.addPropertyChangeListener(listener);
	}
	
	public void addPropertyChangeListener(String key, PropertyChangeListener listener) {
		pcs.addPropertyChangeListener(key, listener);
	}
	
	public void removePropertyChangeListener(PropertyChangeListener listener) {
		pcs.removePropertyChangeListener(listener);
	}
	
	public void removePropertyChangeListener(String key, PropertyChangeListener listener) {
		pcs.removePropertyChangeListener(key, listener);
	}

	/* Properties / Set Values */

	public void clearProperty(String key) {
		int valueCount = getPropertyValueCount(key);
		Object oldValue = null;
		if (valueCount == 0) {
			return;
		} else if (valueCount == 1) {
			oldValue = getString(key);
		} else if (valueCount > 1) {
			oldValue = getStringList(key);
		}
		clearPropertyValues(key);
		setModified(true);
		checkFirePropertyChange(key, oldValue, null);
	}

	public void addProperty(String key, Object value) {
		addPropertyValue(key, value);
		setModified(true);
		pcs.firePropertyChange(key, null, value);
	}

	public void addProperties(String key, List values) {
		addPropertyValues(key, values);
		setModified(true);
		pcs.firePropertyChange(key, null, values);
	}
	
	public void addProperties(String key, Iterator values) {
		addPropertyValues(key, values);
		setModified(true);
		pcs.firePropertyChange(key, null, values);
	}

	public void setProperty(String key, Object value) {
		String oldValue = null;
		oldValue = getString(key);
		config.setProperty(key, value.toString());
		setModified(true);
		checkFirePropertyChange(key, oldValue, value);
	}

	public void setProperties(String key, List values) {
		List<String> oldValues = getStringList(key);
		setPropertyValues(key, values);
		setModified(true);
		checkFirePropertyChange(key, oldValues, values);
	}
	
	public void setProperties(String key, Iterator values) {
		List<String> oldValues = getStringList(key);
		setPropertyValues(key, values);
		setModified(true);
		checkFirePropertyChange(key, oldValues, values);
	}

	/* Keys */

	public boolean containsKey(String key) {
		return config.containsKey(key);
	}

	public List<String> getKeyList() {
		return getKeyList(null);
	}

	public List<String> getKeyList(String prefix) {
		Enumeration keyIterator = config.propertyNames();
		ArrayList<String> result = new ArrayList<String>();
		
		while (keyIterator.hasMoreElements()) {
			String next = (String) keyIterator.nextElement();
			if ((prefix != null) && !next.startsWith(prefix)) {
				continue;
			}
			result.add(next);
		}
		return result;
	}

	/* Get Values */

	public String getString(String key) {
		return config.getProperty(key);
	}

	public String getString(String key, String defaultValue) {
		return config.getProperty(key, defaultValue);
	}

	public List<String> getStringList(String key) {
		return getPropertyValues(key);
	}

	public List<String> getStringList(String key, List<String> defaultValues) {
		List<String> result = getPropertyValues(key);
		if (result == null) {
			result = defaultValues;
		}
		return result;
	}

	public boolean getBoolean(String key) {
		String strValue = config.getProperty(key);
		boolean boolValue = false;
		
		if (strValue != null) {
			boolValue = Boolean.valueOf(strValue).booleanValue();
		}
		
		return boolValue;
	}

	public boolean getBoolean(String key, boolean defaultValue) {
		String strValue = config.getProperty(key);
		boolean boolValue = false;
		
		if (strValue != null) {
			boolValue = Boolean.valueOf(strValue).booleanValue();
		} else {
			boolValue = defaultValue;
		}
		
		return boolValue;
	}

	public byte getByte(String key) {
		String strValue = config.getProperty(key);
		byte byteValue = 0;
		
		if (strValue != null) {
			byteValue = Byte.valueOf(strValue).byteValue();
		}
		
		return byteValue;
	}

	public byte getByte(String key, byte defaultValue) {
		String strValue = config.getProperty(key);
		byte byteValue = 0;
		
		if (strValue != null) {
			byteValue = Byte.valueOf(strValue).byteValue();
		} else {
			byteValue = defaultValue;
		}
		
		return byteValue;
	}

	public double getDouble(String key) {
		String strValue = config.getProperty(key);
		double doubleValue = 0;
		
		if (strValue != null) {
			doubleValue = Double.valueOf(strValue).doubleValue();
		}
		
		return doubleValue;
	}

	public double getDouble(String key, double defaultValue) {
		String strValue = config.getProperty(key);
		double doubleValue = 0;
		
		if (strValue != null) {
			doubleValue = Double.valueOf(strValue).doubleValue();
		} else {
			doubleValue = defaultValue;
		}
		
		return doubleValue;
	}

	public float getFloat(String key) {
		String strValue = config.getProperty(key);
		float floatValue = 0;
		
		if (strValue != null) {
			floatValue = Float.valueOf(strValue).floatValue();
		}
		
		return floatValue;
	}

	public float getFloat(String key, float defaultValue) {
		String strValue = config.getProperty(key);
		float floatValue = 0;
		
		if (strValue != null) {
			floatValue = Float.valueOf(strValue).floatValue();
		} else {
			floatValue = defaultValue;
		}
		
		return floatValue;
	}

	public int getInt(String key) {
		String strValue = config.getProperty(key);
		int intValue = 0;
		
		if (strValue != null) {
			intValue = Integer.valueOf(strValue).intValue();
		}
		
		return intValue;
	}

	public int getInt(String key, int defaultValue) {
		String strValue = config.getProperty(key);
		int intValue = 0;
		
		if (strValue != null) {
			intValue = Integer.valueOf(strValue).intValue();
		} else {
			intValue = defaultValue;
		}
		
		return intValue;
	}

	public long getLong(String key) {
		String strValue = config.getProperty(key);
		long longValue = 0;
		
		if (strValue != null) {
			longValue = Long.valueOf(strValue).longValue();
		}
		
		return longValue;
	}

	public long getLong(String key, long defaultValue) {
		String strValue = config.getProperty(key);
		long longValue = 0;
		
		if (strValue != null) {
			longValue = Long.valueOf(strValue).longValue();
		} else {
			longValue = defaultValue;
		}
		
		return longValue;
	}

	public short getShort(String key) {
		String strValue = config.getProperty(key);
		short shortValue = 0;
		
		if (strValue != null) {
			shortValue = Short.valueOf(strValue).shortValue();
		}
		
		return shortValue;
	}

	public short getShort(String key, short defaultValue) {
		String strValue = config.getProperty(key);
		short shortValue = 0;
		
		if (strValue != null) {
			shortValue = Short.valueOf(strValue).shortValue();
		} else {
			shortValue = defaultValue;
		}
		
		return shortValue;
	}
	
	public URI getURI(String key) {
		try {
			String uri = config.getProperty(key);
			if (uri != null) {
				return new URI(uri);
			}
		} catch (URISyntaxException e) {
		}
		return null;
	}

	public URI getURI(String key, URI defaultValue) {
		URI result = getURI(key);
		if (result == null) {
			return defaultValue;
		}
		return result;
	}
	
	public Color getColor(String key) {
		Color result = null;
		String value = getString(key);

		if (value != null) {
			try {
				if (!value.startsWith("0x"))
					result = Color.decode(value);
				else {
					int rgb = Long.decode(value).intValue();
					result = new Color(rgb);
				}
			} catch (NumberFormatException nfe) {
			}
		}

        return result;
	}

	public Color getColor(String key, Color defaultValue) {
		Color result = getColor(key);
		if (result == null) {
			return defaultValue;
		}
		return result;
	}

}