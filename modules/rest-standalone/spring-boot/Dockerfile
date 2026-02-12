# This Dockerfile assumes the build context is the root of the entrystore project,
# and that the project has been packaged into a fat JAR file (executable jar) before building the image.
# Example usage:
# 1. Run `mvn clean package` from the project root.
# 2. Run `docker build -f modules/rest-standalone/spring-boot/Dockerfile .`


FROM eclipse-temurin:21-jre-alpine


WORKDIR /srv/entrystore

# Copy the pre-built JAR from the build context (project root) into the container.
COPY modules/rest-standalone/spring-boot/target/*-exec.jar entrystore.jar

# App config will be attached by docker-compose
#COPY config-localhost/docker-spring/entrystore.properties entrystore.properties


# Below command means that the config paths in the entrystore.properties must match below paths
RUN mkdir -p \
      ./data/store \
      ./data/store-prov \
      ./data/files \
      ./data/solr \
      ./data/backup


EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "entrystore.jar"]

# Keep the container running indefinitely so you can attach a shell and test
#ENTRYPOINT ["tail", "-f", "/dev/null"]
