# Running EntryStore with Docker

## Prerequisites

- Docker
- Java 25
- Maven 3.6+

## Building

All commands below should be run from the **project root** directory.

The build script builds the project and the Docker image in one step. The version is read from `VERSION.txt`.

```bash
./docker/build.sh
```

Or build manually:

```bash
mvn clean package -DskipTests
docker build \
  -f docker/Dockerfile \
  --build-arg ENTRYSTORE_VERSION=$(cat VERSION.txt) \
  -t metasolutions/entrystore:$(cat VERSION.txt) \
  .
```

## Running

```bash
docker run -p 8080:8080 \
  -v $(pwd)/entrystore.properties:/srv/entrystore/entrystore.properties \
  -v entrystore-data:/srv/entrystore/data \
  metasolutions/entrystore:$(cat VERSION.txt)
```

This starts EntryStore on port 8080 with a local `entrystore.properties` file and a named volume for persistent data.

To load configuration from a remote URL instead:

```bash
docker run -p 8080:8080 \
  -e ENTRYSTORE_CONFIG_URI=https://config.example.org/entrystore.properties \
  -v entrystore-data:/srv/entrystore/data \
  metasolutions/entrystore:$(cat VERSION.txt)
```

See the sections below for details on configuration and data volumes.

## Configuration

EntryStore is configured via an `entrystore.properties` file. A documented example is available at `modules/rest/spring-boot/src/main/resources/entrystore.properties_example`.

Copy it and adjust the settings for your environment:

```bash
cp modules/rest/spring-boot/src/main/resources/entrystore.properties_example entrystore.properties
```

Key settings to configure:

| Property | Description | Docker default path |
|---|---|---|
| `entrystore.baseurl.folder` | External base URL | - |
| `entrystore.repository.store.type` | Store type (`native`, `memory`) | - |
| `entrystore.repository.store.path` | Path to RDF store | `file:///srv/entrystore/data/store/` |
| `entrystore.data.folder` | Path to uploaded files | `/srv/entrystore/data/files/` |
| `entrystore.solr.url` | Solr URL | - |
| `entrystore.auth.adminpw` | Admin password override | - |

The configuration can be provided in three ways (in order of preference):

### Via `ENTRYSTORE_CONFIG_URI` environment variable

Supports `http://`, `https://`, `file://` URLs and local file paths. This is compatible with the Restlet-based version of EntryStore.

```bash
docker run -p 8080:8080 \
  -e ENTRYSTORE_CONFIG_URI=https://config.example.org/entrystore.properties \
  -v entrystore-data:/srv/entrystore/data \
  metasolutions/entrystore:$(cat VERSION.txt)
```

### Via volume mount

Mount the properties file directly into the container:

```bash
docker run -p 8080:8080 \
  -v $(pwd)/entrystore.properties:/srv/entrystore/entrystore.properties \
  -v entrystore-data:/srv/entrystore/data \
  metasolutions/entrystore:$(cat VERSION.txt)
```

### Via command-line property overrides

Individual properties can be passed as arguments without a properties file:

```bash
docker run -p 8080:8080 \
  metasolutions/entrystore:$(cat VERSION.txt) \
  --entrystore.auth.adminpw=mysecretpassword \
  --entrystore.baseurl.folder=https://example.org/store/
```

These can also be combined with a properties file to override specific values.

## Data volumes

The container uses the following paths for persistent data:

| Path | Content |
|---|---|
| `/srv/entrystore/data/store` | RDF triple store |
| `/srv/entrystore/data/store-prov` | Provenance store |
| `/srv/entrystore/data/files` | Uploaded files |
| `/srv/entrystore/data/solr` | Solr index (if using embedded Solr) |
| `/srv/entrystore/data/backup` | Backups |

Mount a volume or bind mount to `/srv/entrystore/data` to persist all data, or mount individual subdirectories as needed.

## JVM options

Pass JVM options via the `JAVA_TOOL_OPTIONS` environment variable:

```bash
docker run -p 8080:8080 \
  -e JAVA_TOOL_OPTIONS="-Xmx2g -Xms512m" \
  -e ENTRYSTORE_CONFIG_URI=https://config.example.org/entrystore.properties \
  -v entrystore-data:/srv/entrystore/data \
  metasolutions/entrystore:$(cat VERSION.txt)
```
