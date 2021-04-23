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

package org.entrystore.config;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

/**
 * This class consists exclusively of static methods that operate on or return
 * collections.
 * 
 * <p>
 * The methods of this class all throw a <tt>IllegalArgumentException</tt>
 * if the collections provided to them are null.
 * 
 * @author Hannes Ebner
 */
public class Configurations {

	// Suppresses default constructor, ensuring non-instantiability.
	private Configurations() {
	}

	/**
	 * Returns a synchronized (thread-safe) list backed by the specified
     * Config.
	 * 
	 * @param config The configuration to be wrapped.
	 * @return A synchronized view of the specified config.
	 */
	public static Config synchronizedConfig(Config config) {
		return new SynchronizedConfiguration(config);
	}

	/**
	 * Synchronized wrapper.
	 * 
	 * @author Hannes Ebner
	 */
	static class SynchronizedConfiguration implements Config {

		private final Object mutex;

		private final Config config;

		/**
		 * @param c Configuration to be synchronized.
		 */
		SynchronizedConfiguration(Config c) {
			if (c == null) {
				throw new IllegalArgumentException("Configuration must not be null");
			}
			config = c;
			mutex = this;
		}

		/**
		 * @param c Configuration to synchronize.
		 * @param mutex Object (mutex) to synchronized on.
		 */
		SynchronizedConfiguration(Config c, Object mutex) {
			if ((c == null) || (mutex == null)) {
				throw new IllegalArgumentException("Configuration must not be null");
			}
			config = c;
			this.mutex = mutex;
		}
		
		/**
		 * @see org.entrystore.config.Config#addPropertyChangeListener(java.beans.PropertyChangeListener)
		 */
		public void addPropertyChangeListener(PropertyChangeListener listener) {
			synchronized (mutex) {
				config.addPropertyChangeListener(listener);
			}
		}
		
		/**
		 * @see org.entrystore.config.Config#addPropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
		 */
		public void addPropertyChangeListener(String key, PropertyChangeListener listener) {
			synchronized (mutex) {
				config.addPropertyChangeListener(key, listener);
			}
		}
		
		/**
		 * @see org.entrystore.config.Config#removePropertyChangeListener(java.beans.PropertyChangeListener)
		 */
		public void removePropertyChangeListener(PropertyChangeListener listener) {
			synchronized (mutex) {
				config.removePropertyChangeListener(listener);
			}
		}
		
		/**
		 * @see org.entrystore.config.Config#removePropertyChangeListener(java.lang.String, java.beans.PropertyChangeListener)
		 */
		public void removePropertyChangeListener(String key, PropertyChangeListener listener) {
			synchronized (mutex) {
				config.removePropertyChangeListener(key, listener);
			}
		}

		/**
		 * @see org.entrystore.config.Config#addProperties(java.lang.String, java.util.List)
		 */
		public void addProperties(String key, List values) {
			synchronized (mutex) {
				config.addProperties(key, values);
			}
		}
		
		/**
		 * @see org.entrystore.config.Config#addProperties(java.lang.String, java.util.Iterator)
		 */
		public void addProperties(String key, Iterator values) {
			synchronized (mutex) {
				config.addProperties(key, values);
			}
		}

		/**
		 * @see org.entrystore.config.Config#addProperty(java.lang.String, java.lang.Object)
		 */
		public void addProperty(String key, Object value) {
			synchronized (mutex) {
				config.addProperty(key, value);
			}
		}

		/**
		 * @see org.entrystore.config.Config#clear()
		 */
		public void clear() {
			synchronized (mutex) {
				config.clear();
			}
		}

		/**
		 * @see org.entrystore.config.Config#clearProperty(java.lang.String)
		 */
		public void clearProperty(String key) {
			synchronized (mutex) {
				config.clearProperty(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#containsKey(java.lang.String)
		 */
		public boolean containsKey(String key) {
			synchronized (mutex) {
				return config.containsKey(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getBoolean(java.lang.String)
		 */
		public boolean getBoolean(String key) {
			synchronized (mutex) {
				return config.getBoolean(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getBoolean(java.lang.String, boolean)
		 */
		public boolean getBoolean(String key, boolean defaultValue) {
			synchronized (mutex) {
				return config.getBoolean(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getByte(java.lang.String)
		 */
		public byte getByte(String key) {
			synchronized (mutex) {
				return config.getByte(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getByte(java.lang.String, byte)
		 */
		public byte getByte(String key, byte defaultValue) {
			synchronized (mutex) {
				return config.getByte(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getDouble(java.lang.String)
		 */
		public double getDouble(String key) {
			synchronized (mutex) {
				return config.getDouble(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getDouble(java.lang.String, double)
		 */
		public double getDouble(String key, double defaultValue) {
			synchronized (mutex) {
				return config.getDouble(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getFloat(java.lang.String)
		 */
		public float getFloat(String key) {
			synchronized (mutex) {
				return config.getFloat(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getFloat(java.lang.String, float)
		 */
		public float getFloat(String key, float defaultValue) {
			synchronized (mutex) {
				return config.getFloat(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getInt(java.lang.String)
		 */
		public int getInt(String key) {
			synchronized (mutex) {
				return config.getInt(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getInt(java.lang.String, int)
		 */
		public int getInt(String key, int defaultValue) {
			synchronized (mutex) {
				return config.getInt(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getKeyList()
		 */
		public List getKeyList() {
			synchronized (mutex) {
				return config.getKeyList();
			}
		}

		/**
		 * @see org.entrystore.config.Config#getKeyList(java.lang.String)
		 */
		public List getKeyList(String prefix) {
			synchronized (mutex) {
				return config.getKeyList(prefix);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getLong(java.lang.String)
		 */
		public long getLong(String key) {
			synchronized (mutex) {
				return config.getLong(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getLong(java.lang.String, long)
		 */
		public long getLong(String key, long defaultValue) {
			synchronized (mutex) {
				return config.getLong(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getShort(java.lang.String)
		 */
		public short getShort(String key) {
			synchronized (mutex) {
				return config.getShort(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getShort(java.lang.String, short)
		 */
		public short getShort(String key, short defaultValue) {
			synchronized (mutex) {
				return config.getShort(key, defaultValue);
			}
		}
		
		/**
		 * @see org.entrystore.config.Config#getURI(java.lang.String)
		 */
		public URI getURI(String key) {
			synchronized (mutex) {
				return config.getURI(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getURI(java.lang.String, java.net.URI)
		 */
		public URI getURI(String key, URI defaultValue) {
			synchronized (mutex) {
				return config.getURI(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getURL(String)
		 */
		public URL getURL(String key) {
			synchronized (mutex) {
				return config.getURL(key);
			}
        }

		/**
		 * @see org.entrystore.config.Config#getURL(String, URL) 
		 */
		public URL getURL(String key, URL defaultValue) {
			synchronized (mutex) {
				return config.getURL(key, defaultValue);
			}
		}

        /**
		 * @see org.entrystore.config.Config#getColor(java.lang.String)
		 */
		public Color getColor(String key) {
			synchronized (mutex) {
				return config.getColor(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getColor(java.lang.String, java.awt.Color)
		 */
		public Color getColor(String key, Color defaultValue) {
			synchronized (mutex) {
				return config.getColor(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getString(java.lang.String)
		 */
		public String getString(String key) {
			synchronized (mutex) {
				return config.getString(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getString(java.lang.String, java.lang.String)
		 */
		public String getString(String key, String defaultValue) {
			synchronized (mutex) {
				return config.getString(key, defaultValue);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getStringList(java.lang.String)
		 */
		public List getStringList(String key) {
			synchronized (mutex) {
				return config.getStringList(key);
			}
		}

		/**
		 * @see org.entrystore.config.Config#getStringList(java.lang.String, java.util.List)
		 */
		public List getStringList(String key, List defaultValues) {
			synchronized (mutex) {
				return config.getStringList(key, defaultValues);
			}
		}

		/**
		 * @see org.entrystore.config.Config#isEmpty()
		 */
		public boolean isEmpty() {
			synchronized (mutex) {
				return config.isEmpty();
			}
		}

		/**
		 * @see org.entrystore.config.Config#isModified()
		 */
		public boolean isModified() {
			synchronized (mutex) {
				return config.isModified();
			}
		}

		/**
		 * @see org.entrystore.config.Config#load(java.net.URL)
		 */
		public void load(URL configURL) throws IOException {
			synchronized (mutex) {
				config.load(configURL);
			}
		}

		/**
		 * @see org.entrystore.config.Config#save(java.net.URL)
		 */
		public void save(URL configURL) throws IOException {
			synchronized (mutex) {
				config.save(configURL);
			}
		}

		/**
		 * @see org.entrystore.config.Config#setProperties(java.lang.String, java.util.List)
		 */
		public void setProperties(String key, List values) {
			synchronized (mutex) {
				config.setProperties(key, values);
			}
		}
		
		/**
		 * @see org.entrystore.config.Config#setProperties(java.lang.String, java.util.Iterator)
		 */
		public void setProperties(String key, Iterator values) {
			synchronized (mutex) {
				config.setProperties(key, values);
			}
		}

		/**
		 * @see org.entrystore.config.Config#setProperty(java.lang.String, java.lang.Object)
		 */
		public void setProperty(String key, Object value) {
			synchronized (mutex) {
				config.setProperty(key, value);
			}
		}

	}

}